package kr.bujobank.scm;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;
public class PasswordsTest {
    @Test public void saltedHashesAndVerification(){String password="correct horse battery";String a=Passwords.hash(password),b=Passwords.hash(password);assertNotEquals(a,b);assertTrue(Passwords.verify(password,a));assertFalse(Passwords.verify("incorrect-password",a));assertFalse(Passwords.verify(password,"broken"));}
    @Test public void passwordBoundaries(){assertThrows(IllegalArgumentException.class,()->Passwords.validate("short"));Passwords.validate("abcdefghijkl");assertThrows(IllegalArgumentException.class,()->Passwords.validate(String.join("",Collections.nCopies(129,"x"))));}
    @Test public void tokenAndAmounts(){assertTrue(Passwords.token().matches("[A-Za-z0-9_-]{43}"));Map<String,String[]> p=new HashMap<>();p.put("price",new String[]{"12.34"});assertEquals("12.34",ScmService.amount(p,"price").toPlainString());p.put("price",new String[]{"12.345"});assertThrows(IllegalArgumentException.class,()->ScmService.amount(p,"price"));}
}