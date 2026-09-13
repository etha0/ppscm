package kr.bujobank.scm;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS=600000;
    private Passwords() {}
    public static void validate(String password) {if(password==null||password.length()<12||password.length()>128)throw new IllegalArgumentException("비밀번호는 12~128자로 입력하세요.");}
    public static String token() {byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String password) {validate(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));}
    public static boolean verify(String password,String hash) {
        if(password==null||password.length()>128||hash==null)return false;
        try {String[] parts=hash.split("\\$");if(parts.length!=4||!parts[0].equals("pbkdf2-sha256"))return false;int rounds=Integer.parseInt(parts[1]);if(rounds<100000||rounds>2000000)return false;return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derive(password,Base64.getDecoder().decode(parts[2]),rounds));} catch(RuntimeException e){return false;}
    }
    private static byte[] derive(String password,byte[] salt,int iterations) {PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
}
