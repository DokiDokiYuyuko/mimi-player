package app.mimi.player;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import org.mozilla.universalchardet.UniversalDetector;

public final class TextCodec {
    public static String decode(byte[] bytes){
        if(bytes.length>=4&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe&&bytes[2]==0&&bytes[3]==0)return strip(new String(bytes,4,bytes.length-4,Charset.forName("UTF-32LE")));
        if(bytes.length>=4&&bytes[0]==0&&bytes[1]==0&&bytes[2]==(byte)0xfe&&bytes[3]==(byte)0xff)return strip(new String(bytes,4,bytes.length-4,Charset.forName("UTF-32BE")));
        if(bytes.length>=2&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)return strip(new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE));
        if(bytes.length>=2&&bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)return strip(new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE));
        try{return strip(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException ignored){}
        UniversalDetector detector=new UniversalDetector(null);detector.handleData(bytes,0,bytes.length);detector.dataEnd();String charset=detector.getDetectedCharset();
        if(charset==null)charset="GB18030";
        if(charset.equalsIgnoreCase("SHIFT_JIS"))charset="windows-31j";
        try{return strip(new String(bytes,Charset.forName(charset)));}catch(Exception ignored){return strip(new String(bytes,Charset.forName("GB18030")));}
    }
    private static String strip(String text){return text.replace("\uFEFF","").replace("\r\n","\n").replace('\r','\n');}
    private TextCodec(){}
}
