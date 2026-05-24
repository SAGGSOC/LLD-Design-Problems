import atm.service.BankService;

public enum InstrumentType{
    BANK, CARD
}
abstract class Instrument{
    int instrumentId;
    int userId;
    InstrumentType instrumentType;

    public Instrument(){}

    public Instrument(int instrumentId, int userId, InstrumentType instrumentType){
        this.instrumentId = instrumentId;
        this.userId = userId;
        this.instrumentType = instrumentType;
    }
}
class BankInstrument extends Instrument {
    String bankAccNum;
    String ifscCode;
 public BankInstrument(){
 }
 public BankInstrument(int instrumentId,int userId, InstrumentType type, String bankAccNum, String ifscCode){
    super(instrumentId,userId,type);
    this.bankAccNum = bankAccNum;
    this.ifscCode = ifscCode;
 }
 public String getBankAccountNum(){
    return bankAccNum;
 }
 public String getIfscCode(){
    return ifscCode;
 }
 public void setBankAccountNumber(String bankAccountNumber) {
    this.bankAccountNumber = bankAccountNumber;
 }
public void setIfscCode(String ifscCode) {
    this.ifscCode = ifscCode;
}

   
}
class CardInstrument extends Instrument{
    String cardNum;
    String cvv;
    public CardInstrument(){}
    public CardInstrument(int instrumentId, int userId, InstrumentType type, String cardNum, String cvv){
        super(instrumentId, userId, type);
        this.cardNum = cardNum;
        this.cvv = cvv;
    }

    public String getCardNum(){ return cardNum; }

    public String getCvv(){ 
        return cvv; 
    }

    public void setCvv(String cvv) {
        this.cvv = cvv;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }
    
}
abstract class Instrument{
    int instrumentId;
    int userId;
    InstrumentType instrumentType;

    public Instrument(){
    }
    public Instrument(int instrumentId, int userId, InstrumentType instrumentType){
        this.instrumentId = instrumentId;
        this.userId = userId;
        this.instrumentType = instrumentType;
    }
    public int getInstrumentId(){
        return instrumentId;
    }
    public void setInstrumentId(int instrumentId){
        this.instrumentId = instrumentId;
    }
    public int getUserId(){
        return userId;
    }
    public void setUserId(int userId){
        this.userId = userId;
    }
    public InstrumentType getInstrumentType(){
        return instrumentType;
    }
    public void setInstrumentType(InstrumentType instrumentType) {
        this.instrumentType = instrumentType;
    }
}
public class InstrumentController{
    InstrumentServiceFactory instrumentServiceFactory;
    
}
class BankService extends InstrumentService{

}
class CardService extends InstrumentService{

}
class PaymentGateway {
    
}
