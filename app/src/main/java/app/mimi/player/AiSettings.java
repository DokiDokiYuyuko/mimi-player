package app.mimi.player;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials stay in native storage, encrypted by the device's Android Keystore. */
public final class AiSettings {
    private static final String ALIAS="mimi.openrouter.v1";
    private final AtomicFile file;
    private JSONObject data;
    public AiSettings(Context context){
        file=new AtomicFile(new File(context.getFilesDir(),"ai-settings.json"));
        try{data=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));}catch(Exception e){data=new JSONObject();}
    }
    public synchronized JSONObject publicConfig(){
        return LibraryStore.obj("hasKey",!data.optString("encryptedKey").isEmpty(),"sttModel",data.optString("sttModel","openai/whisper-large-v3"),
            "translationModel",data.optString("translationModel","tencent/hy-mt2-30b-a3b"),"fallbackModel",data.optString("fallbackModel",""),
            "language",data.optString("language","auto"),"bilingual",data.optBoolean("bilingual",false),"output",data.optString("output","vtt"),
            "maxCost",data.optDouble("maxCost",3),"glossary",data.optString("glossary",""));
    }
    public synchronized void update(JSONObject values) throws Exception {
        JSONObject next=new JSONObject(data.toString());
        for(String key:new String[]{"sttModel","translationModel","fallbackModel"})if(values.has(key)){
            String value=values.getString(key).trim();if(!(key.equals("fallbackModel")&&value.isEmpty())&&!value.matches("[A-Za-z0-9._:-]+/[A-Za-z0-9._:/-]+"))throw new IOException("模型 ID 格式不正确");next.put(key,value);
        }
        if(values.has("language")){String value=values.getString("language");if(!value.equals("auto")&&!value.matches("[a-z]{2,3}"))throw new IOException("语言代码无效");next.put("language",value);}
        if(values.has("output")){String value=values.getString("output");if(!value.equals("vtt")&&!value.equals("lrc"))throw new IOException("字幕格式无效");next.put("output",value);}
        if(values.has("bilingual"))next.put("bilingual",values.getBoolean("bilingual"));
        if(values.has("maxCost"))next.put("maxCost",Math.max(.05,Math.min(20,values.getDouble("maxCost"))));
        if(values.has("glossary")){String value=values.getString("glossary").trim();if(value.length()>2000)throw new IOException("术语表请控制在 2000 字以内");next.put("glossary",value);}
        if(values.optBoolean("clearKey")){next.remove("encryptedKey");next.remove("iv");}
        String key=values.optString("apiKey","").trim();
        if(!key.isEmpty()){if(key.length()<16||key.length()>512||key.matches(".*\\s.*"))throw new IOException("API Key 格式不正确");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret());next.put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));next.put("encryptedKey",Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));}
        FileOutputStream out=null;try{out=file.startWrite();out.write(next.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);data=next;}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    public synchronized String key() throws Exception {
        if(data.optString("encryptedKey").isEmpty())throw new IOException("请先在字幕设置中填写 OpenRouter API Key");
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,Base64.decode(data.getString("iv"),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(data.getString("encryptedKey"),Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){throw new IOException("无法读取已保存的 API Key，请重新填写");}
    }
    private static javax.crypto.SecretKey secret() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
}
