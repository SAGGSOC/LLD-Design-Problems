# Identity and Access Management (IAM) System — System Design

---

## 1. Requirements

### Functional Requirements

- `createPrincipal(type, orgId, details) → principalId` — register a human user or service identity
- `authenticate(credentials) → authToken` — verify identity, issue short-lived token
- `authorize(principalId, action, resource) → allow/deny` — evaluate if action is permitted
- `createRole(orgId, roleName, policies[]) → roleId` — define a role with attached policies
- `createPolicy(orgId, statements[]) → policyId` — define fine-grained permission policy
- `attachPolicy(principalId|roleId, policyId) → confirmation` — bind policy to principal or role
- `assumeRole(principalId, roleId) → temporaryCredentials` — principal assumes a role, gets scoped token
- `listAuditLogs(orgId, filters) → auditEvents[]` — query who did what, when
- `rotateServiceCredentials(principalId) → newCredentials` — rotate service identity keys
- `revokeToken(tokenId) → confirmation` — immediately invalidate a token
- `createOrg(name, adminUser) → orgId` — provision a new tenant

### Non-Functional Requirements

| Requirement | Target |
|---|---|
| AuthZ latency (policy evaluation) | < 10ms p99 |
| AuthN latency (token issuance) | < 100ms p99 |
| Principals per org | 100K+ users, 10K+ services |
| Policies per org | 50K+ |
| AuthZ requests/sec (global) | 500K+ |
| Availability | 99.99% (AuthZ on critical path) |
| Consistency | Eventual OK for audit; strong for policy eval |
| Token lifetime | 15 min (short-lived), refresh via rotation |
| Multi-region | Active-active for AuthZ reads |

### Clarifying Questions

| Question | Assumed Answer |
|---|---|
| Multi-tenant? | Yes — orgs are fully isolated tenants |
| Federation (SAML/OIDC)? | Yes — humans via external IdP, services via internal |
| Policy language? | AWS IAM-style: Effect/Action/Resource/Condition |
| Hierarchical resources? | Yes — org/project/resource path-based |
| Cross-account access? | Yes — via trust policies on roles |
| MFA? | Yes — required for sensitive operations |
| Temporary credentials? | Yes — no long-lived secrets for services |

---

## 2. High-Level Architecture

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  Admin UI    │   │   CLI Tool   │   │  Microservice│
│  (Console)   │   │  (iam-cli)   │   │  (API call)  │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │                  │                   │
       ▼                  ▼                   ▼
┌─────────────────────────────────────────────────────┐
│                   API Gateway                        │
│          (rate limiting, TLS termination)            │
└──────────────────────────┬──────────────────────────┘
                           │
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
┌─────────────┐   ┌──────────────┐   ┌──────────────────┐
│  AuthN      │   │  AuthZ       │   │  Management      │
│  Service    │   │  Service     │   │  Service         │
│             │   │  (Policy     │   │  (CRUD for       │
│ (login,     │   │   Evaluator) │   │   principals,    │
│  token      │   │              │   │   roles,         │
│  issuance,  │   │              │   │   policies)      │
│  MFA)       │   │              │   │                  │
└──────┬──────┘   └──────┬───────┘   └────────┬─────────┘
       │                 │                     │
       ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────┐
│                    Data Layer                         │
├─────────────┬──────────────┬────────────────────────┤
│ DynamoDB    │ Redis        │ S3                      │
│ (principals,│ (policy      │ (audit logs,            │
│  roles,     │  cache,      │  compliance             │
│  policies,  │  token       │  reports)               │
│  audit)     │  blacklist)  │                         │
└─────────────┴──────────────┴────────────────────────┘
```

### Component Responsibilities

```
┌──────────────────┬──────────────────────────────────────────────────────┐
│ Component        │ Responsibility                                       │
├──────────────────┼──────────────────────────────────────────────────────┤
│ API Gateway      │ TLS, rate limiting, request routing, API key check   │
│ AuthN Service    │ Login (password/OIDC/SAML), MFA, token issuance,    │
│                  │ token refresh, credential rotation                   │
│ AuthZ Service    │ Policy evaluation engine, permission checks,         │
│                  │ role assumption, resource-level access decisions     │
│ Management Svc   │ CRUD for principals, roles, policies, orgs;         │
│                  │ admin operations, bulk imports                       │
│ DynamoDB         │ Source of truth for all IAM entities                 │
│ Redis            │ Hot cache for policies (AuthZ < 10ms), token        │
│                  │ blacklist for revocation                             │
│ S3               │ Long-term audit log storage, compliance reports      │
│ Audit Pipeline   │ Async: DDB Streams → Lambda → S3 (+ Athena query)  │
└──────────────────┴──────────────────────────────────────────────────────┘
```

---

## 3. Core Data Model

### 3.1 Entity Relationship

```
                         ┌───────────┐
                         │    Org     │
                         │ (tenant)  │
                         └─────┬─────┘
                               │ 1:N
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
       ┌────────────┐  ┌────────────┐   ┌────────────┐
       │ Principal   │  │   Role     │   │  Policy    │
       │ (user or   │  │            │   │            │
       │  service)  │  │            │   │            │
       └──────┬─────┘  └──────┬─────┘   └──────┬─────┘
              │               │                │
              │    N:M        │     N:M        │
              ├───────────────┤                │
              │  (principal   │                │
              │   assumes     │                │
              │   roles)      │                │
              │               ├────────────────┘
              │               │  (role has
              │               │   policies)
              │               │
              └───────┬───────┘
                      │
                      ▼
              ┌────────────────┐
              │ Policy         │
              │ Statement      │
              │                │
              │ Effect: Allow  │
              │   or Deny      │
              │ Action: list   │
              │ Resource: ARN  │
              │ Condition: map │
              └────────────────┘
```

### 3.2 Entity Definitions

```
┌──────────────────────────┐
│          Org              │
│──────────────────────────│
│ - orgId: String           │
│ - name: String            │
│ - status: OrgStatus       │
│ - rootPrincipalId: String │
│ - createdAt: Instant      │
│ - settings: Map           │
│   (MFA policy, password   │
│    policy, session length) │
└──────────────────────────┘

┌──────────────────────────┐
│       Principal           │
│──────────────────────────│
│ - principalId: String     │  e.g. "usr-abc123" or "svc-xyz789"
│ - orgId: String           │
│ - type: PrincipalType     │  USER | SERVICE
│ - name: String            │
│ - email: String           │  (users only)
│ - credentialHash: String  │  (bcrypt for users, HMAC key hash for services)
│ - mfaEnabled: boolean     │
│ - mfaSecret: String       │  (encrypted TOTP secret)
│ - status: PrincipalStatus │  ACTIVE | SUSPENDED | DELETED
│ - directPolicyIds: List   │  policies attached directly
│ - roleIds: List           │  roles assigned to this principal
│ - tags: Map<String,String>│
│ - lastAuthAt: Instant     │
│ - createdAt: Instant      │
└──────────────────────────┘

┌──────────────────────────┐
│          Role             │
│──────────────────────────│
│ - roleId: String          │  e.g. "role-admin-001"
│ - orgId: String           │
│ - roleName: String        │  e.g. "ProjectAdmin"
│ - description: String     │
│ - policyIds: List<String> │  attached policies
│ - trustPolicy: Policy     │  who can assume this role
│ - maxSessionDuration: int │  seconds (default 3600)
│ - tags: Map               │
│ - createdAt: Instant      │
└──────────────────────────┘

┌──────────────────────────┐
│         Policy            │
│──────────────────────────│
│ - policyId: String        │  e.g. "pol-readonly-s3"
│ - orgId: String           │
│ - policyName: String      │
│ - version: int            │  for versioned updates
│ - statements: List        │  list of PolicyStatement
│ - createdAt: Instant      │
│ - updatedAt: Instant      │
└──────────────────────────┘

┌──────────────────────────┐
│    PolicyStatement        │
│──────────────────────────│
│ - sid: String             │  statement ID (optional label)
│ - effect: Effect          │  ALLOW | DENY
│ - actions: List<String>   │  e.g. ["s3:GetObject", "s3:ListBucket"]
│ - resources: List<String> │  e.g. ["arn:org:s3:::my-bucket/*"]
│ - conditions: Map         │  e.g. {"IpAddress": {"sourceIp": "10.0.0.0/8"}}
└──────────────────────────┘
```

### 3.3 Resource Naming (ARN Format)

```
arn:<orgId>:<service>:<region>:<projectId>:<resourceType>/<resourceId>

Examples:
  arn:org-42:compute:us-east-1:proj-7:instance/i-abc123
  arn:org-42:storage:*:proj-7:bucket/my-data/*
  arn:org-42:iam::proj-7:role/ProjectAdmin
  arn:org-42:api:us-west-2:proj-7:endpoint/orders/*

Wildcards:
  *          — matches any single segment
  resource/* — matches all sub-resources
```

---

## 4. Enums

```java
public enum PrincipalType     { USER, SERVICE }
public enum PrincipalStatus   { ACTIVE, SUSPENDED, DELETED }
public enum OrgStatus         { ACTIVE, SUSPENDED }
public enum Effect            { ALLOW, DENY }
public enum TokenType         { ACCESS, REFRESH, ASSUME_ROLE }
public enum AuditAction       { LOGIN, LOGOUT, ASSUME_ROLE, API_CALL, POLICY_CHANGE,
                                ROLE_CHANGE, PRINCIPAL_CHANGE, TOKEN_REVOKE, MFA_VERIFY }
```

---

## 5. DynamoDB Table Design

### 5.1 Principals Table (On-Demand)

```
Table Name: Principals

PK: orgId (S)               e.g. "org-42"
SK: principalId (S)         e.g. "usr-abc123"

Attributes:
  type              (S)     USER | SERVICE
  name              (S)     "Alice Johnson"
  email             (S)     "[email]" (users only)
  credentialHash    (S)     bcrypt hash (users) or HMAC key hash (services)
  mfaEnabled        (BOOL)  true
  mfaSecret         (S)     encrypted TOTP secret
  status            (S)     ACTIVE
  directPolicyIds   (SS)    {"pol-001", "pol-002"}
  roleIds           (SS)    {"role-admin-001"}
  tags              (M)     {"team": "platform", "env": "prod"}
  lastAuthAt        (S)     ISO-8601
  createdAt         (S)     ISO-8601

GSI: EmailIndex
  PK: email (S)
  SK: orgId (S)
  → Login lookup: find principal by email across orgs

GSI: TypeStatusIndex
  PK: orgId#type (S)        e.g. "org-42#SERVICE"
  SK: status (S)
  → List all active service identities in an org
```

### 5.2 Roles Table (On-Demand)

```
Table Name: Roles

PK: orgId (S)               e.g. "org-42"
SK: roleId (S)              e.g. "role-admin-001"

Attributes:
  roleName          (S)     "ProjectAdmin"
  description       (S)     "Full access to project resources"
  policyIds         (SS)    {"pol-001", "pol-003"}
  trustPolicy       (S)     JSON — who can assume this role
  maxSessionDuration (N)    3600
  tags              (M)     {"managed-by": "platform-team"}
  createdAt         (S)     ISO-8601

GSI: RoleNameIndex
  PK: orgId (S)
  SK: roleName (S)
  → Lookup role by name (for CLI/API: "assume role ProjectAdmin")
```

### 5.3 Policies Table (On-Demand)

```
Table Name: Policies

PK: orgId (S)               e.g. "org-42"
SK: policyId (S)            e.g. "pol-readonly-s3"

Attributes:
  policyName        (S)     "S3ReadOnly"
  version           (N)     3
  statements        (L)     List of statement maps:
                            [
                              {
                                "sid": "AllowS3Read",
                                "effect": "ALLOW",
                                "actions": ["s3:GetObject", "s3:ListBucket"],
                                "resources": ["arn:org-42:storage:*:*:bucket/*"],
                                "conditions": {}
                              }
                            ]
  createdAt         (S)     ISO-8601
  updatedAt         (S)     ISO-8601

GSI: PolicyNameIndex
  PK: orgId (S)
  SK: policyName (S)
  → Lookup by friendly name
```

### 5.4 Policy Attachments Table (On-Demand)

```
Table Name: PolicyAttachments

PK: orgId#targetId (S)      e.g. "org-42#usr-abc123" or "org-42#role-admin-001"
SK: policyId (S)            e.g. "pol-readonly-s3"

Attributes:
  targetType        (S)     PRINCIPAL | ROLE
  attachedAt        (S)     ISO-8601
  attachedBy        (S)     principalId of admin who attached

GSI: PolicyTargetsIndex
  PK: orgId#policyId (S)    e.g. "org-42#pol-readonly-s3"
  SK: targetId (S)
  → "Which principals/roles have this policy?" (for impact analysis)
```

### 5.5 Tokens Table (On-Demand, TTL enabled)

```
Table Name: Tokens

PK: tokenId (S)             e.g. "tok-abc123xyz"  (JTI from JWT)
SK: principalId (S)         e.g. "usr-abc123"

Attributes:
  orgId             (S)     "org-42"
  tokenType         (S)     ACCESS | REFRESH | ASSUME_ROLE
  assumedRoleId     (S)     "role-admin-001" (only for ASSUME_ROLE tokens)
  issuedAt          (S)     ISO-8601
  expiresAt         (S)     ISO-8601
  revoked           (BOOL)  false
  ttl               (N)     Unix epoch — DDB auto-deletes expired tokens

GSI: PrincipalTokenIndex
  PK: principalId (S)
  SK: issuedAt (S)
  → List all active tokens for a principal (for revocation)

Note: TTL attribute auto-cleans expired tokens. Revoked tokens
are also pushed to Redis blacklist for fast AuthZ rejection.
```

### 5.6 Audit Logs Table (On-Demand)

```
Table Name: AuditLogs

PK: orgId (S)               e.g. "org-42"
SK: timestamp#eventId (S)   e.g. "2026-03-16T12:00:00Z#evt-abc123"

Attributes:
  eventId           (S)     "evt-abc123"
  principalId       (S)     "usr-abc123"
  action            (S)     "API_CALL"
  resource          (S)     "arn:org-42:storage:us-east-1:proj-7:bucket/data"
  result            (S)     "ALLOW" | "DENY"
  sourceIp          (S)     "10.0.1.42"
  userAgent         (S)     "iam-cli/2.1"
  details           (M)     {requestParams, responseCode, etc.}

GSI: PrincipalAuditIndex
  PK: orgId#principalId (S)  e.g. "org-42#usr-abc123"
  SK: timestamp (S)
  → "Show me everything Alice did in the last 24 hours"

GSI: ResourceAuditIndex
  PK: orgId#resource (S)     e.g. "org-42#arn:...:bucket/data"
  SK: timestamp (S)
  → "Who accessed this bucket today?"

Note: Hot data in DDB (last 90 days). Older logs archived to S3
via DDB Streams → Lambda → S3 (Parquet). Query via Athena.
```

### 5.7 Orgs Table (On-Demand)

```
Table Name: Orgs

PK: orgId (S)               e.g. "org-42"

Attributes:
  name              (S)     "Acme Corp"
  status            (S)     ACTIVE | SUSPENDED
  rootPrincipalId   (S)     "usr-root-001"
  settings          (M)     {
                              "mfaRequired": true,
                              "passwordMinLength": 12,
                              "sessionMaxDuration": 3600,
                              "ipAllowList": ["10.0.0.0/8"]
                            }
  createdAt         (S)     ISO-8601
```

### DynamoDB Table Summary

```
┌─────────────────────┬──────────────────┬──────────────────┬──────────────────────────┐
│ Table               │ PK               │ SK               │ GSIs                     │
├─────────────────────┼──────────────────┼──────────────────┼──────────────────────────┤
│ Principals          │ orgId            │ principalId      │ EmailIndex,              │
│                     │                  │                  │ TypeStatusIndex          │
│ Roles               │ orgId            │ roleId           │ RoleNameIndex            │
│ Policies            │ orgId            │ policyId         │ PolicyNameIndex          │
│ PolicyAttachments   │ orgId#targetId   │ policyId         │ PolicyTargetsIndex       │
│ Tokens              │ tokenId          │ principalId      │ PrincipalTokenIndex      │
│ AuditLogs           │ orgId            │ timestamp#evtId  │ PrincipalAuditIndex,     │
│                     │                  │                  │ ResourceAuditIndex       │
│ Orgs                │ orgId            │ —                │ —                        │
└─────────────────────┴──────────────────┴──────────────────┴──────────────────────────┘

All tables On-Demand. Tokens table has TTL enabled for auto-expiry cleanup.
AuditLogs archived to S3 after 90 days via DDB Streams.
```

---

## 6. Token Format (JWT)

### Access Token Structure

```
Header:
{
  "alg": "RS256",
  "kid": "key-2026-03",        ← rotating signing key ID
  "typ": "JWT"
}

Payload:
{
  "jti": "tok-abc123xyz",       ← unique token ID (stored in Tokens table)
  "iss": "iam.platform.internal",
  "sub": "usr-abc123",          ← principalId
  "org": "org-42",              ← tenant isolation
  "type": "ACCESS",
  "roles": ["role-admin-001"],  ← currently assumed roles
  "iat": 1710590400,            ← issued at
  "exp": 1710591300,            ← expires in 15 min
  "ctx": {                      ← optional context
    "sourceIp": "10.0.1.42",
    "mfaVerified": true,
    "sessionId": "sess-xyz"
  }
}

Signature:
  RS256(header + payload, privateKey)
```

### Assume-Role Token

```
When a principal assumes a role, a NEW scoped token is issued:

{
  "jti": "tok-role-session-001",
  "sub": "usr-abc123",
  "org": "org-42",
  "type": "ASSUME_ROLE",
  "assumedRole": "role-admin-001",
  "policies": ["pol-001", "pol-003"],  ← effective policies from role
  "exp": 1710594000,                   ← bounded by role's maxSessionDuration
  "originalToken": "tok-abc123xyz"     ← parent token reference
}

The AuthZ service evaluates policies from the ROLE, not the principal's
direct policies. This is the scoping mechanism.
```

### Service Identity Token (No Long-Lived Secrets)

```
Services authenticate via short-lived signed requests (like AWS SigV4):

1. Service has a rotatable HMAC key pair (keyId + secretKey)
2. Service signs each request: HMAC-SHA256(canonicalRequest, secretKey)
3. AuthN service verifies signature, issues 15-min access token
4. Token used for subsequent API calls until expiry
5. Keys rotated every 24h automatically (rotateServiceCredentials)

No long-lived bearer tokens. If a key is compromised:
  → revokeToken + rotateServiceCredentials → immediate lockout
```

---

## 7. AuthN Flow — Authentication

### Flow 1: Human User Login (Password + MFA)

```
User enters email + password
     │
     ▼
┌──────────────────────────┐
│ AuthN Service             │
│                          │
│ 1. Lookup principal by   │
│    email (EmailIndex)    │
│                          │
│ 2. Verify password:      │
│    bcrypt.verify(input,  │
│    credentialHash)       │
│                          │
│ 3. Check principal       │
│    status == ACTIVE      │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐    No     ┌──────────────────┐
│ MFA enabled?             │──────────▶│ Issue access +   │
└────────┬─────────────────┘           │ refresh tokens   │
         │ Yes                         └──────────────────┘
         ▼
┌──────────────────────────┐
│ Prompt for TOTP code     │
│                          │
│ Verify: TOTP(mfaSecret,  │
│   currentTime) == input  │
│                          │
│ If valid:                │
│  → Issue tokens with     │
│    mfaVerified=true      │
│                          │
│ If invalid:              │
│  → 401 + increment       │
│    failed attempt counter│
│  → Lock after 5 failures │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Write to Tokens table    │
│ Write to AuditLogs       │
│ (LOGIN event)            │
│                          │
│ Return:                  │
│  accessToken  (15 min)   │
│  refreshToken (24 hours) │
└──────────────────────────┘
```

### Flow 2: Service Identity Authentication

```
Service makes API request
     │
     ▼
┌──────────────────────────┐
│ Request includes:        │
│  - keyId header          │
│  - timestamp             │
│  - HMAC-SHA256 signature │
│    of canonical request  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ AuthN Service             │
│                          │
│ 1. Lookup service by     │
│    keyId → principalId   │
│                          │
│ 2. Retrieve secretKey    │
│    hash from Principals  │
│                          │
│ 3. Recompute signature   │
│    from canonical request│
│                          │
│ 4. Compare signatures    │
│                          │
│ 5. Check timestamp skew  │
│    (reject if > 5 min)   │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ If valid:                │
│  → Issue short-lived     │
│    access token (15 min) │
│  → No refresh token for  │
│    services (re-sign)    │
│                          │
│ Audit log: LOGIN event   │
└──────────────────────────┘
```

### Flow 3: Federated Login (OIDC / SAML)

```
User clicks "Login with Corporate SSO"
     │
     ▼
┌──────────────────────────┐
│ Redirect to external IdP │
│ (Okta, Azure AD, etc.)  │
│                          │
│ IdP authenticates user   │
│ → returns id_token or    │
│   SAML assertion         │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ AuthN Service             │
│                          │
│ 1. Validate IdP token:   │
│    - Verify signature    │
│      against IdP JWKS    │
│    - Check issuer, aud,  │
│      expiry              │
│                          │
│ 2. Extract claims:       │
│    email, groups, etc.   │
│                          │
│ 3. Map to internal       │
│    principal:            │
│    email → EmailIndex    │
│    lookup                │
│                          │
│ 4. If no principal:      │
│    auto-provision (JIT)  │
│    based on org settings │
│                          │
│ 5. Map IdP groups to     │
│    internal roles        │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Issue platform tokens    │
│ with mapped roles        │
│                          │
│ Audit: FEDERATED_LOGIN   │
└──────────────────────────┘
```

---

## 8. AuthZ Flow — Authorization & Policy Evaluation

### Flow 4: API Request Authorization

```
Microservice receives API request with Bearer token
     │
     ▼
┌──────────────────────────┐
│ Step 1: Token Validation │
│                          │
│ 1. Parse JWT, verify     │
│    RS256 signature       │
│    against public key    │
│    (cached, rotated via  │
│    kid header)           │
│                          │
│ 2. Check expiry (exp)    │
│                          │
│ 3. Check token blacklist │
│    in Redis (O(1) lookup)│
│    → if revoked: 401     │
│                          │
│ 4. Extract: sub, org,    │
│    roles, type           │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Step 2: Gather Policies  │
│                          │
│ Build effective policy   │
│ set for this principal:  │
│                          │
│ If ASSUME_ROLE token:    │
│  → policies from the     │
│    assumed role only      │
│                          │
│ If ACCESS token:         │
│  → direct policies on    │
│    principal             │
│  → policies from all     │
│    assigned roles        │
│                          │
│ Cache: Redis stores      │
│ compiled policy set per  │
│ principal (invalidated   │
│ on policy/role change)   │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Step 3: Policy Evaluation│
│                          │
│ For request:             │
│   action = "s3:GetObject"│
│   resource = "arn:org-42 │
│     :storage:...:bucket/ │
│     data/file.csv"       │
│                          │
│ Evaluate all statements: │
│                          │
│ 1. Collect all DENY      │
│    statements that match │
│    → if ANY deny: DENY   │
│    (explicit deny wins)  │
│                          │
│ 2. Collect all ALLOW     │
│    statements that match │
│    → if ANY allow: ALLOW │
│                          │
│ 3. If no match: implicit │
│    DENY (default deny)   │
│                          │
│ 4. Evaluate conditions   │
│    (IP range, time,      │
│    MFA status, tags)     │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Step 4: Decision + Audit │
│                          │
│ Return ALLOW or DENY     │
│                          │
│ Async: write AuditLog    │
│ (fire-and-forget to SQS  │
│  → Lambda → DDB/S3)     │
└──────────────────────────┘
```

### Policy Evaluation Algorithm (Pseudo-code)

```
function evaluate(principalId, action, resource, context):

    // 1. Gather all applicable policies
    policies = []
    policies.addAll(getDirectPolicies(principalId))       // from Principals table
    for role in getPrincipalRoles(principalId):
        policies.addAll(getRolePolicies(role))             // from Roles → Policies

    // 2. Flatten all statements
    allStatements = policies.flatMap(p -> p.statements)

    // 3. Explicit DENY check (highest priority)
    for stmt in allStatements:
        if stmt.effect == DENY
           AND matchesAction(stmt.actions, action)
           AND matchesResource(stmt.resources, resource)
           AND evaluateConditions(stmt.conditions, context):
            return DENY    // explicit deny — game over

    // 4. Look for an ALLOW
    for stmt in allStatements:
        if stmt.effect == ALLOW
           AND matchesAction(stmt.actions, action)
           AND matchesResource(stmt.resources, resource)
           AND evaluateConditions(stmt.conditions, context):
            return ALLOW

    // 5. Default: implicit deny
    return DENY


function matchesAction(patterns, action):
    // "s3:*" matches "s3:GetObject"
    // "*" matches everything
    return patterns.any(p -> wildcardMatch(p, action))


function matchesResource(patterns, resource):
    // "arn:org-42:storage:*:*:bucket/data/*" matches
    // "arn:org-42:storage:us-east-1:proj-7:bucket/data/file.csv"
    return patterns.any(p -> arnWildcardMatch(p, resource))


function evaluateConditions(conditions, context):
    // Example conditions:
    //   {"IpAddress": {"sourceIp": "10.0.0.0/8"}}
    //   {"Bool": {"mfaVerified": "true"}}
    //   {"DateLessThan": {"currentTime": "2026-12-31T23:59:59Z"}}
    if conditions is empty: return true
    return conditions.all((operator, keyValueMap) ->
        keyValueMap.all((key, expected) ->
            applyOperator(operator, context.get(key), expected)
        )
    )
```

### Evaluation Priority

```
┌─────────────────────────────────────────────────────────┐
│              Policy Evaluation Order                     │
│                                                         │
│  1. Explicit DENY   →  always wins, stops evaluation    │
│  2. Explicit ALLOW  →  grants access if no deny         │
│  3. Implicit DENY   →  default if nothing matches       │
│                                                         │
│  Precedence: DENY > ALLOW > implicit DENY               │
│                                                         │
│  Org-level policies (SCPs) evaluated BEFORE principal   │
│  policies — can restrict even if principal has ALLOW    │
└─────────────────────────────────────────────────────────┘
```

### Flow 5: Assume Role

```
Service or user wants elevated permissions
     │
     ▼
┌──────────────────────────┐
│ assumeRole(principalId,  │
│   roleId)                │
│                          │
│ 1. Verify principal is   │
│    authenticated (valid  │
│    access token)         │
│                          │
│ 2. Load role from Roles  │
│    table                 │
│                          │
│ 3. Evaluate trustPolicy: │
│    "Is this principal    │
│     allowed to assume    │
│     this role?"          │
│                          │
│    Trust policy example: │
│    {                     │
│      "effect": "ALLOW",  │
│      "principals": [     │
│        "svc-deploy-*",   │
│        "usr-alice"       │
│      ],                  │
│      "conditions": {     │
│        "Bool": {         │
│          "mfaVerified":  │
│            "true"        │
│        }                 │
│      }                   │
│    }                     │
│                          │
│ 4. If trust check passes:│
│    → Issue ASSUME_ROLE   │
│      token scoped to     │
│      role's policies     │
│    → Bounded by role's   │
│      maxSessionDuration  │
│                          │
│ 5. Audit: ASSUME_ROLE    │
└──────────────────────────┘
```

---

## 9. Auditing, Revocation & Key Rotation

### Audit Pipeline

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ AuthN/AuthZ  │     │    SQS       │     │   Lambda     │
│ Services     │────▶│  Audit Queue │────▶│  Processor   │
│ (emit events)│     │              │     │              │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
                                    ┌─────────────┼─────────────┐
                                    ▼             ▼             ▼
                             ┌───────────┐ ┌───────────┐ ┌───────────┐
                             │ DynamoDB  │ │ S3        │ │ CloudWatch│
                             │ AuditLogs │ │ (Parquet  │ │ Metrics   │
                             │ (hot,     │ │  archive, │ │ (anomaly  │
                             │  90 days) │ │  Athena)  │ │  alerts)  │
                             └───────────┘ └───────────┘ └───────────┘

Audit events are fire-and-forget from the AuthZ hot path.
SQS provides durability. Lambda writes to DDB + S3 + metrics.
```

### What Gets Audited

```
┌──────────────────────┬──────────────────────────────────────────┐
│ Event                │ Details Captured                          │
├──────────────────────┼──────────────────────────────────────────┤
│ LOGIN                │ principalId, method (password/SSO/SigV4),│
│                      │ sourceIp, success/failure, MFA used      │
│ LOGOUT               │ principalId, sessionDuration             │
│ ASSUME_ROLE          │ principalId, roleId, duration, sourceIp  │
│ API_CALL             │ principalId, action, resource, ALLOW/DENY│
│                      │ sourceIp, userAgent, latency             │
│ POLICY_CHANGE        │ who changed, policyId, before/after diff │
│ ROLE_CHANGE          │ who changed, roleId, policies added/removed│
│ PRINCIPAL_CHANGE     │ who changed, what changed (status, roles)│
│ TOKEN_REVOKE         │ who revoked, tokenId, reason             │
│ CREDENTIAL_ROTATE    │ principalId, old keyId, new keyId        │
│ MFA_VERIFY           │ principalId, success/failure             │
└──────────────────────┴──────────────────────────────────────────┘
```

### Token Revocation

```
revokeToken(tokenId) called by admin or automated system
     │
     ▼
┌──────────────────────────┐
│ 1. Update Tokens table:  │
│    revoked = true        │
│                          │
│ 2. Push tokenId to Redis │
│    blacklist with TTL =  │
│    remaining token life  │
│    (no need to keep      │
│    after natural expiry) │
│                          │
│ 3. Audit: TOKEN_REVOKE   │
└──────────────────────────┘

On every AuthZ check:
  → Redis.exists("blacklist:" + jti)
  → If found: reject immediately (401)
  → O(1) lookup, < 1ms

Bulk revocation (e.g., suspend a principal):
  → Query PrincipalTokenIndex for all active tokens
  → Batch revoke all
  → Update principal status = SUSPENDED
```

### Key Rotation

```
Signing Key Rotation (RS256 key pairs):

┌──────────────────────────────────────────────────────────┐
│ Key Lifecycle                                             │
│                                                          │
│ 1. Generate new key pair every 30 days                   │
│    → Store in AWS Secrets Manager / KMS                  │
│    → Assign new kid (e.g., "key-2026-04")               │
│                                                          │
│ 2. New tokens signed with new key                        │
│    → kid in JWT header identifies which key              │
│                                                          │
│ 3. Old key kept for validation for 24 hours              │
│    (tokens issued with old key still valid until expiry) │
│                                                          │
│ 4. After 24h grace period: old key decommissioned        │
│                                                          │
│ JWKS endpoint (/.well-known/jwks.json) always serves     │
│ current + previous key for seamless rotation             │
└──────────────────────────────────────────────────────────┘

Service Credential Rotation:

┌──────────────────────────────────────────────────────────┐
│ 1. rotateServiceCredentials(principalId)                 │
│                                                          │
│ 2. Generate new HMAC key pair                            │
│    → newKeyId + newSecretKey                             │
│                                                          │
│ 3. Store new key, keep old key active for 1 hour         │
│    (dual-key window for zero-downtime rotation)          │
│                                                          │
│ 4. Service picks up new credentials (via config push     │
│    or secrets manager poll)                              │
│                                                          │
│ 5. After 1h: old key deactivated                         │
│                                                          │
│ 6. Automated: cron triggers rotation every 24h           │
│    per service identity                                  │
│                                                          │
│ 7. Audit: CREDENTIAL_ROTATE event                        │
└──────────────────────────────────────────────────────────┘
```

---

## 10. Caching Strategy (AuthZ < 10ms)

```
┌──────────────────────────────────────────────────────────┐
│                  Cache Layers                             │
├──────────────────┬───────────────────────────────────────┤
│ Layer            │ What's Cached                          │
├──────────────────┼───────────────────────────────────────┤
│ Redis (shared)   │ Compiled policy set per principal     │
│                  │ Key: "policies:{orgId}:{principalId}" │
│                  │ TTL: 5 minutes                        │
│                  │                                       │
│                  │ Token blacklist                       │
│                  │ Key: "blacklist:{jti}"                │
│                  │ TTL: remaining token lifetime         │
│                  │                                       │
│                  │ JWKS public keys                      │
│                  │ Key: "jwks:current"                   │
│                  │ TTL: 1 hour                           │
├──────────────────┼───────────────────────────────────────┤
│ Local (in-proc)  │ Parsed JWKS keys (avoid Redis call    │
│                  │ for signature verification)           │
│                  │ TTL: 60 seconds                       │
│                  │                                       │
│                  │ Org settings (MFA policy, etc.)       │
│                  │ TTL: 5 minutes                        │
└──────────────────┴───────────────────────────────────────┘

Cache Invalidation:
  When a policy or role is updated:
    → Management Service publishes event to SNS
    → AuthZ Service subscribes, deletes affected cache keys
    → Key pattern: "policies:{orgId}:{principalId}"
    → For role changes: invalidate ALL principals with that role
       (query PolicyAttachments GSI to find affected principals)
```

---

## 11. DDB Write Triggers — Who Writes What & When

```
┌──────────────────────┬───────────────┬───────────────────┬───────────────────────────┐
│ Action               │ Triggered By  │ Tables Written    │ Write Type               │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createOrg            │ Super Admin   │ Orgs              │ PutItem                  │
│                      │               │ Principals        │ PutItem (root user)      │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createPrincipal      │ Org Admin     │ Principals        │ PutItem                  │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ authenticate         │ User/Service  │ Tokens            │ PutItem (new token)      │
│                      │               │ Principals        │ UpdateItem (lastAuthAt)  │
│                      │               │ AuditLogs         │ PutItem (LOGIN event)    │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createRole           │ Org Admin     │ Roles             │ PutItem                  │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ createPolicy         │ Org Admin     │ Policies          │ PutItem                  │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ attachPolicy         │ Org Admin     │ PolicyAttachments │ PutItem                  │
│                      │               │ Redis             │ Delete cache key         │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ assumeRole           │ User/Service  │ Tokens            │ PutItem (scoped token)   │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ authorize (API call) │ AuthZ Service │ AuditLogs         │ PutItem (async via SQS)  │
│                      │               │ Redis             │ Read (cache hit/miss)    │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ revokeToken          │ Admin/System  │ Tokens            │ UpdateItem (revoked)     │
│                      │               │ Redis             │ Set blacklist key        │
│                      │               │ AuditLogs         │ PutItem (async)          │
├──────────────────────┼───────────────┼───────────────────┼───────────────────────────┤
│ rotateCredentials    │ Cron/Admin    │ Principals        │ UpdateItem (new key)     │
│                      │               │ AuditLogs         │ PutItem (async)          │
└──────────────────────┴───────────────┴───────────────────┴───────────────────────────┘
```

---

## 12. Key Trade-offs & Scaling Considerations

### Trade-offs

```
┌──────────────────────────┬──────────────────────┬──────────────────────────┐
│ Decision                 │ Chose                │ Trade-off                │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Token format             │ JWT (self-contained) │ + AuthZ doesn't need DB  │
│                          │                      │   call for every request │
│                          │                      │ - Can't instantly revoke │
│                          │                      │   (need blacklist)       │
│                          │                      │ - Token size grows with  │
│                          │                      │   claims                │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Token lifetime           │ 15 min access,       │ + Short window if leaked │
│                          │ 24h refresh          │ - More refresh traffic   │
│                          │                      │ - Services must handle   │
│                          │                      │   token refresh logic    │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Policy eval location     │ Centralized AuthZ    │ + Single policy engine   │
│                          │ service              │ + Consistent evaluation  │
│                          │                      │ - Extra network hop      │
│                          │                      │ - AuthZ is on critical   │
│                          │                      │   path (must be fast)    │
│                          │                      │                          │
│ Alternative: sidecar     │                      │ + No network hop         │
│ (policy agent per host)  │                      │ - Policy sync complexity │
│                          │                      │ - More memory per host   │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Policy storage           │ DynamoDB + Redis     │ + DDB: durable, scalable │
│                          │ cache                │ + Redis: < 10ms reads    │
│                          │                      │ - Cache invalidation     │
│                          │                      │   complexity             │
│                          │                      │ - Eventual consistency   │
│                          │                      │   window (up to 5 min)  │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Audit: sync vs async     │ Async (SQS → Lambda) │ + No latency impact on  │
│                          │                      │   AuthZ hot path         │
│                          │                      │ - Audit logs may lag     │
│                          │                      │   by seconds             │
│                          │                      │ - Must handle SQS DLQ   │
│                          │                      │   for failed writes      │
├──────────────────────────┼──────────────────────┼──────────────────────────┤
│ Multi-tenant isolation   │ Shared tables with   │ + Cost efficient         │
│                          │ orgId as PK prefix   │ + Simple operations      │
│                          │                      │ - Noisy neighbor risk    │
│                          │                      │ - Must enforce orgId in  │
│                          │                      │   every query            │
│                          │                      │                          │
│ Alternative: table-per-  │                      │ + Full isolation         │
│ tenant                   │                      │ - Operational overhead   │
│                          │                      │ - Harder to manage at    │
│                          │                      │   scale (10K+ tenants)  │
└──────────────────────────┴──────────────────────┴──────────────────────────┘
```

### Scaling Considerations

```
┌──────────────────────────────────────────────────────────────────┐
│ AuthZ Service (hottest path — 500K+ req/sec)                     │
│                                                                  │
│ • Stateless — horizontal scale behind ALB                        │
│ • Redis cluster for policy cache (ElastiCache, multi-AZ)        │
│ • Local in-process cache for JWKS keys (avoid Redis for          │
│   every signature check)                                         │
│ • Policy evaluation is CPU-bound (wildcard matching) —           │
│   pre-compile policies into a trie/radix tree at cache load     │
│ • Multi-region: deploy AuthZ + Redis in each region             │
│   (active-active reads, eventual consistency on policy sync)    │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ DynamoDB Scaling                                                 │
│                                                                  │
│ • On-Demand mode for all tables (spiky traffic patterns)        │
│ • Principals/Roles/Policies: read-heavy, mostly served from     │
│   Redis cache. DDB handles cache misses + writes                │
│ • AuditLogs: write-heavy (every API call). Partition key =      │
│   orgId — large orgs may hotspot. Mitigation: add random        │
│   suffix to PK for write sharding (e.g., orgId#shard-N)        │
│ • Tokens table: TTL auto-cleanup prevents unbounded growth      │
│ • Global Tables for multi-region (Principals, Roles, Policies)  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Availability                                                     │
│                                                                  │
│ • AuthZ is on the critical path for every API call              │
│   → Must be 99.99%+ available                                   │
│   → Fail-open vs fail-closed decision:                          │
│     Default: FAIL-CLOSED (deny if AuthZ is down)               │
│     Exception: read-only operations can fail-open with          │
│     cached last-known-good policy                               │
│                                                                  │
│ • Circuit breaker on AuthZ → DDB path                           │
│   If DDB is slow: serve from Redis cache (stale but available)  │
│                                                                  │
│ • Redis cluster with replicas — if primary fails, replica       │
│   promotes. AuthZ falls back to DDB on total Redis failure      │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Security Hardening                                               │
│                                                                  │
│ • All credentials encrypted at rest (KMS)                       │
│ • Passwords: bcrypt with cost factor 12                         │
│ • Service keys: HMAC-SHA256, never stored in plaintext          │
│ • MFA secrets: AES-256 encrypted, decrypted only during verify  │
│ • JWT signing keys: RSA-2048, stored in KMS, rotated monthly    │
│ • All inter-service communication over mTLS                     │
│ • Rate limiting on AuthN endpoints (prevent brute force)        │
│   → 5 failed attempts → 15 min lockout                         │
│ • IP allowlisting configurable per org                          │
└──────────────────────────────────────────────────────────────────┘
```

---

## 13. End-to-End Scenario

```
Acme Corp (org-42) onboards:

1. Super admin creates org-42 with root user "usr-root-001"
   → Orgs: PutItem, Principals: PutItem

2. Root user creates policy "S3ReadOnly":
   { effect: ALLOW, actions: ["s3:Get*", "s3:List*"], resources: ["arn:org-42:storage:*:*:*"] }
   → Policies: PutItem

3. Root user creates role "DataAnalyst" with S3ReadOnly attached
   Trust policy: { principals: ["usr-*"], conditions: { mfaVerified: true } }
   → Roles: PutItem, PolicyAttachments: PutItem

4. Root user creates service identity "svc-etl-pipeline"
   → Principals: PutItem (type=SERVICE, HMAC key generated)

5. Alice (usr-alice) logs in with password + MFA
   → AuthN verifies bcrypt + TOTP → issues JWT (15 min)
   → Tokens: PutItem, AuditLogs: LOGIN

6. Alice assumes role "DataAnalyst"
   → Trust policy check: usr-alice matches "usr-*", MFA verified ✓
   → New ASSUME_ROLE token issued, scoped to S3ReadOnly policies
   → Tokens: PutItem, AuditLogs: ASSUME_ROLE

7. Alice calls GET /storage/bucket/data/report.csv
   → API Gateway forwards to Storage Service
   → Storage Service calls AuthZ: authorize("usr-alice", "s3:GetObject",
       "arn:org-42:storage:us-east-1:proj-7:bucket/data/report.csv")
   → AuthZ: Redis cache hit for Alice's compiled policies
   → S3ReadOnly ALLOW matches s3:Get* + arn:...:bucket/* → ALLOW
   → AuditLogs: API_CALL (async via SQS)

8. svc-etl-pipeline signs request with HMAC key
   → AuthN verifies signature → issues 15-min token
   → Service calls internal API, AuthZ evaluates → ALLOW
   → 24h later: cron triggers rotateServiceCredentials
   → New HMAC key issued, old key valid for 1h grace period

9. Admin suspects Alice's token leaked
   → revokeToken("tok-abc123xyz")
   → Redis blacklist set, Tokens table updated
   → Next request with that token → Redis blacklist hit → 401
```
