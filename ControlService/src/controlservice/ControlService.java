package controlservice;

import cardTools.CardManager;
import cardTools.Util;

import javax.smartcardio.*;

/**
 *
 * @author Lubomir Hrbacek
 */
public class ControlService {

    
    final static byte CLA_LOGAPPLET = (byte) 0xB4;
    final static byte INS_SEND_LOG_LEN = (byte) 0x50;
    final static byte INS_SEND_LOG = (byte) 0x51;
    final static byte INS_MODIFY_LOG = (byte) 0x52;
    
    final static byte APDU_T = (byte) 0x00;
    final static byte RSPS_T = (byte) 0x01;
    
    final static short LOG_OFFSET_ITER = (short) 0x00;
    final static short LOG_OFFSET_TYPE = (short) 0x01;
    final static short LOG_OFFSET_LEN = (short) 0x02;
    final static short LOG_OFFSET_DATA = (short) 0x04;
    
    final static short APDU_HEAD_LEN = (short) 0x05;
    final static short APDU_DATA_MAX_LEN = (short) 0xff;

    
    // section with atrs

    private static String REPLAY_CARD_ATR1 = "";
    private static String REPLAY_CARD_ATR2 = "";
    private static String TARGET_CARD_ATR1 = "";
    private static String TARGET_CARD_ATR2 = "";

    private static String REPLAY_CARD_ATR_ARR[] = { REPLAY_CARD_ATR1, REPLAY_CARD_ATR2 };
    private static String TARGET_CARD_ATR_ARR[] = { TARGET_CARD_ATR1, TARGET_CARD_ATR2 };
  
    private byte[] log = null;
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            ControlService main = new ControlService();
            
            if (args.length > 0 && args[0].equals("-a")) {
                main.automate();
            }
            
            final CardManager cardMngrReplay = main.ConnectPhysicalCard(REPLAY_CARD_ATR_ARR, false);
            
            main.getCardSize(cardMngrReplay);
            
            if (args.length > 0 && args[0].equals("-r")) {
                System.out.println("RESETING LOG");
                main.ResetLog();
                main.writeInReplay(cardMngrReplay);
            }
            
            if (args.length > 0 && args[0].equals("-w")) {
                main.writeInLog(cardMngrReplay, args[1]);                        
            } 
             
            main.readOutReplay(cardMngrReplay);
            main.printLog();
            
            final CardManager cardMngrTarget = main.ConnectPhysicalCard(TARGET_CARD_ATR_ARR, false);
            
            main.communicateTarget(cardMngrTarget);
            main.printLog();
            
            main.writeInReplay(cardMngrReplay);
            
            cardMngrReplay.Disconnect(true);
            cardMngrTarget.Disconnect(true);
            
        } catch (Exception ex) {
            System.out.println("Exception : " + ex);
            System.out.println(ex.getCause());
        }
    }
    
    public void writeInLog(CardManager cardMngr, String str) throws Exception {
        System.out.println(str);
        byte[] str_byte = Util.hexStringToByteArray(str);
        java.lang.System.arraycopy(str_byte, 0, log, 0, str_byte.length);
        writeInReplay(cardMngr);
    }
    
    public void readOutReplay(CardManager cardMngr) throws Exception {
        System.out.println("READOUT");
                 
        byte[] offset = {0, 0};
        ResponseAPDU response = cardMngr.transmit(new CommandAPDU(CLA_LOGAPPLET, INS_SEND_LOG, offset[0], offset[1]));
        while (response.getSW() == 0x9000) {
            short offset_s = Util.getShort(offset, (short) 0);
            byte[] logData = response.getData();
            java.lang.System.arraycopy(logData, 0, log, offset_s, logData.length); // copy logData
            setShort(offset, (short) 0, (short) (offset_s + logData.length)); // set new offset
            response = cardMngr.transmit(new CommandAPDU(CLA_LOGAPPLET, INS_SEND_LOG, offset[0], offset[1]));
        }
    }
    
    public void writeInReplay(CardManager cardMngr) throws Exception {
        System.out.println("WRITEIN");
                
        byte[] offset = {0, 0};
        short offset_s = (short) 0;
        while (log.length > (offset_s = Util.getShort(offset, (short) 0))) {
            short dataLen = (short) (log.length - offset_s) < (short) 255
                ? (short) (log.length - offset_s)
                : (short) 255;
            byte[] data = new byte[dataLen];
            java.lang.System.arraycopy(log, offset_s, data, 0, dataLen);
            ResponseAPDU response = cardMngr.transmit(new CommandAPDU(CLA_LOGAPPLET, INS_MODIFY_LOG, offset[0], offset[1], data));
            if (response.getSW() != 0x9000) {
                throw new Exception();
            }
            setShort(offset, (short) 0, (short) (offset_s + dataLen));
        }
    }
    
    public void communicateTarget(CardManager cardMngr) throws Exception {
        // works only if error sw1 and sw2 does not have any data
        System.out.println("COMMUNICATE");
               
        byte iter = 0;
        short offset = 0;
        short dataLen = 0;
        while (0 != (dataLen = Util.getShort(log, (short) (offset + LOG_OFFSET_LEN)))
               || offset >= log.length) {
            byte[] data = new byte[dataLen];
            java.lang.System.arraycopy(log, offset + LOG_OFFSET_DATA, data, 0, dataLen);
            
            ResponseAPDU response = cardMngr.transmit(new CommandAPDU(data));
            byte[] rspsData = response.getData();
            
            offset = (short) ((short) (offset + LOG_OFFSET_DATA) + dataLen);
            log[offset] = iter;
            log[offset + LOG_OFFSET_TYPE] = RSPS_T;
            
            if (rspsData.length != 0) {
                java.lang.System.arraycopy(rspsData, 0, log, offset + LOG_OFFSET_DATA, rspsData.length);
            }
            
            setShort(log, (short) (offset + LOG_OFFSET_LEN), (short) ((short) rspsData.length + (short) 2));
            setShort(log, (short) (offset + LOG_OFFSET_DATA + rspsData.length), (short) response.getSW());
            offset = (short) ((short) (offset + LOG_OFFSET_DATA) + (short) ((short) rspsData.length + (short) 2));
            iter++;
        }
    }
    
    private void automate() throws Exception {
        byte iter = 0;
        while (iter < 4) {
            CardManager cardMngrReplay;
            try {
                cardMngrReplay = ConnectPhysicalCard(REPLAY_CARD_ATR_ARR, false);
            } catch (Exception e) {
                iter++;
                System.out.println("Connection will be repeated in 5 seconds, insert replay card.");
                Thread.sleep(5000);
                continue;
            }
            iter = 0;
            
            getCardSize(cardMngrReplay);
            readOutReplay(cardMngrReplay);
            
            final CardManager cardMngrTarget = ConnectPhysicalCard(TARGET_CARD_ATR_ARR, false);
            
            communicateTarget(cardMngrTarget);
            printLog();
            writeInReplay(cardMngrReplay);
            
            cardMngrReplay.Disconnect(true);
            cardMngrTarget.Disconnect(true);
            System.out.println("Sleeping 5 seconds, remove replay card.");
            Thread.sleep(5000);
        }
    }
    
    public void printLog() {
        System.out.println("LOG:");
        short ptr = 0;
        while (true) {
            if (ptr >= log.length) {
                break;
            }
            short dataLen = Util.getShort(log, (short) (ptr + 2));
            if (dataLen == 0) {
                break;
            }
            System.out.print(Util.toHex(log, ptr, 1));
            System.out.print(Util.toHex(log, ptr + 1, 1));
            System.out.print(Util.toHex(log, ptr + 2, 2));
            ptr = (short) (ptr + 4);
            for (int i = 0; i < dataLen; i++) {
                System.out.print(Util.toHex(log, ptr+i, 1));
            }
            System.out.println("|");
            ptr = (short) (ptr + dataLen);
        }   
    }
    
    public void changeLog() {
        
    }
    
    private void getCardSize(CardManager cardMngr) throws Exception {
        ResponseAPDU response = cardMngr.transmit(new CommandAPDU(CLA_LOGAPPLET, INS_SEND_LOG_LEN, 0x00, 0x00));
        byte[] data = response.getData();
        short CardSize = Util.getShort(data, (short) 0);
        log = new byte[CardSize];
        java.util.Arrays.fill(log, (byte) 0);
    }  
    
    private boolean CheckATRs(Card card, String[] cardATRs) {
        for (byte i = 0; i < cardATRs.length; i++) {
            if (Util.toHex(card.getATR().getBytes()).equals(cardATRs[i])) {
                  return true;
            }
        }
        return false;
    }
    
    private CardManager ConnectPhysicalCard(String[] cardATRs, boolean debug) throws Exception {
        TerminalFactory factory = TerminalFactory.getDefault();

        try {
            for (CardTerminal t : factory.terminals().list()) {
                if (t.isCardPresent()) {
                    CardManager cardMngr = new CardManager(debug, null);
                    System.out.println("Card found: " + t.getName());
                    System.out.print("Connecting...");
                    Card card = t.connect("*");
                    
                    if (!CheckATRs(card, cardATRs)) {
                        System.out.print("Card has unknown ATR: ");
                        System.out.println(Util.toHex(card.getATR().getBytes()));
                        continue;
                    }
                    
                    System.out.println(" Done.");
                    System.out.print("Establishing channel...");
                    cardMngr.setChannel(card.getBasicChannel());
                    System.out.println(" Done.");
                    return cardMngr;
                }
            }
        } catch (Exception e) {
            System.out.println("Failed.");
            throw e;
        }
        throw new Exception("No card with corresponding ATR found.");
    }
    
    private void ResetLog() {
        java.util.Arrays.fill(log, (byte) 0);
    }
    
    private void setShort(byte[] byteArr, short offset, short value)
            throws ArrayIndexOutOfBoundsException, NullPointerException {
        byteArr[offset] = (byte) (value >> 8);
        byteArr[offset + 1] = (byte) value;
    }
}
