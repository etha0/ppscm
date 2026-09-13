package kr.bujobank.scm;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Numbered JPEG files shared by manual placement and web uploads. */
public final class ProductCodeImages {
    private final Path root;
    public ProductCodeImages(String directory) {
        root=directory==null||directory.trim().isEmpty()?null:Paths.get(directory.trim()).normalize();
        if(root!=null&&!root.isAbsolute()) throw new IllegalArgumentException("SCM_UPLOAD_IMAGES_DIR에는 절대 경로를 지정하세요.");
    }
    public boolean enabled(){return root!=null;}
    private Path path(String code,int sequence) throws IOException {
        if(root==null)throw new IOException("SCM_UPLOAD_IMAGES_DIR 설정이 필요합니다.");
        if(code==null||!code.matches("[A-Za-z0-9_-]{1,30}")||sequence<1||sequence>6)throw new IOException("잘못된 상품 이미지 이름입니다.");
        Path file=root.resolve(code+"_"+sequence+".JPG");
        if(Files.isSymbolicLink(file))throw new IOException("이미지 심볼릭 링크는 사용할 수 없습니다.");
        return file;
    }
    public List<Integer> sequences(String code){
        List<Integer> found=new ArrayList<>();
        for(int i=1;i<=6;i++)try{if(Files.isRegularFile(path(code,i),LinkOption.NOFOLLOW_LINKS))found.add(i);}catch(IOException ignored){}
        return found;
    }
    public boolean exists(String code){return !sequences(code).isEmpty();}
    public byte[] read(String code)throws IOException{
        List<Integer> found=sequences(code);if(found.isEmpty())throw new NoSuchFileException("상품 이미지가 없습니다.");
        return read(code,found.get(0));
    }
    public byte[] read(String code,int sequence)throws IOException{
        Path file=path(code,sequence);
        if(Files.size(file)>5242880)throw new IOException("이미지는 5MB 이하여야 합니다.");
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length<3||(bytes[0]&255)!=255||(bytes[1]&255)!=216||(bytes[2]&255)!=255)throw new IOException("JPG 이미지가 아닙니다.");
        return bytes;
    }
    private void write(Path file,byte[] data)throws IOException{
        Files.createDirectories(root);
        Path temp=Files.createTempFile(root,".image-",".tmp");
        try{Files.write(temp,data);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temp);}
    }
    public Change change(){return new Change();}
    public final class Change implements AutoCloseable {
        private final Map<Path,byte[]> backups=new LinkedHashMap<>();
        private boolean committed;
        private void set(String code,int seq,byte[] bytes)throws IOException{
            Path file=path(code,seq);
            if(!backups.containsKey(file)){
                if(Files.exists(file)&&Files.size(file)>5242880)throw new IOException("기존 이미지가 5MB를 초과합니다.");
                backups.put(file,Files.exists(file)?Files.readAllBytes(file):null);
            }
            if(bytes==null)Files.deleteIfExists(file);else write(file,bytes);
        }
        public void replace(String oldCode,String code,List<byte[]> images)throws IOException{
            if(images.size()>6)throw new IOException("이미지는 최대 6장입니다.");
            if(oldCode!=null&&!oldCode.equals(code)&&exists(code))throw new IOException("변경할 상품코드의 이미지가 이미 존재합니다.");
            if(oldCode!=null&&!oldCode.equals(code))for(int i=1;i<=6;i++)set(oldCode,i,null);
            for(int i=1;i<=6;i++)set(code,i,i<=images.size()?images.get(i-1):null);
        }
        public void rename(String oldCode,String code)throws IOException{
            if(oldCode.equals(code)||!exists(oldCode))return;
            List<byte[]> images=new ArrayList<>();for(int seq:sequences(oldCode))images.add(read(oldCode,seq));
            replace(oldCode,code,images);
        }
        public void commit(){committed=true;}
        public void close()throws IOException{
            if(committed)return;
            IOException failure=null;
            for(Map.Entry<Path,byte[]> e:backups.entrySet())try{if(e.getValue()==null)Files.deleteIfExists(e.getKey());else write(e.getKey(),e.getValue());}catch(IOException ex){failure=ex;}
            if(failure!=null)throw failure;
        }
    }
}