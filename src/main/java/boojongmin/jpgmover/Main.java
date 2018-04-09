package boojongmin.jpgmover;

import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    static Logger errLogger = LoggerFactory.getLogger("Main");
    static Map<String, List<MetaInfo>> result = new HashMap<>();
    static File source;
    static File destination;
    static Set<String> duplicateSet = new HashSet<>();
    static ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 1024);
    static SimpleDateFormat outputFormat = new SimpleDateFormat("hhmmss");
    static int index = 0;

    public static void main(String[] args) throws Exception {
        elapsedTimeChecker(() -> {
            checkInput(args);
            walkFiles(source);
        }, "1. collect file info" );

        printReport();

        elapsedTimeChecker(() -> {
            moveJpgWithErrorHandling();
        }, "2. move files");
        System.out.println("complete");
        walkForDelete(source);
    }

    private static void walkForDelete(File file) {
        if(file.isDirectory()) {
            String[] fileArr = file.list();
            if(fileArr.length > 0) {
                for (File f : file.listFiles()) {
                    walkForDelete(f);
                }
                deleteEmptyFolder(file);
            } else {
                deleteEmptyFolder(file);
            }
        }
    }
    private static void deleteEmptyFolder(File f) {
        if(f.list().length == 0) {
            System.out.println(">>> delete empty folder" + f.getName());
            f.delete();
        }
    }

    private static void elapsedTimeChecker(FacadConsumer fn, String stageName) throws Exception {
        System.out.println(String.format("start -> %s", stageName));
        long startTime = System.currentTimeMillis();
        fn.run(stageName);
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println(String.format("end -> %s, elapsed time %d(ms)", stageName, totalTime));
    }


    private static void printReport() {
        System.out.println(String.format("total image count is %d", result.values().stream().map(x -> x.size()).reduce(0, Integer::sum)));
        System.out.println(String.format("total image size is %.2f Mb",
            result.values().stream().map(x ->
                x.stream()
                    .map(y -> y.getFile().length())
                    .reduce(0L, Long::sum)
            )
            .reduce(0L, Long::sum)
            .doubleValue() / (1000 * 1000)
        ));
    }

    private static void checkInput(String[] args) {
        if(args.length != 2) {
            System.out.println("[error] your input size is not 2.\nexit");
            System.exit(0);
        }
        source = new File(args[0]);
        destination = new File(args[1]);
        if(!source.exists()) {
            System.out.println("first argument is wrong. check image path");
        }

    }

    private static void moveJpgWithErrorHandling() {
        try {
            moveMedia();
        } catch (Exception e) {
            System.out.println(String.format("[error when move jpg] %s", e.getMessage()));
            errLogger.error(String.format("[error when move jpg] %s", e.getMessage()), e);
        }
    }

    private static void moveMedia() {
        if(!destination.exists()) {
            destination.mkdirs();
        }
        Path path = Paths.get(destination.toURI());

        for (String key : result.keySet()) {
            try {
                Path dir = path.resolve(key);
                File file = dir.toFile();
                if (!file.exists()) {
                    file.mkdirs();
                }
                List<MetaInfo> infos = result.get(key);
                for (MetaInfo info : infos) {
                    File target = info.getFile();
                    try {
                        String fileName = target.getName();
                        String baseName = FilenameUtils.getBaseName(fileName);
                        String extensionName = FilenameUtils.getExtension(fileName);
                        String format = outputFormat.format(info.getCreated());
                        String resultFileName = String.format("%s-%s", baseName, format);
                        if (!"".equals(extensionName)) {
                            resultFileName += "." + extensionName;
                        }
                        Path targetPath = Paths.get(target.toURI());
                        Path movePath = dir.resolve(resultFileName);
                        if(movePath.toFile().exists()) {
                            targetPath.toFile().delete();
                        } else {
                            Files.move(targetPath, movePath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        errLogger.info(String.format("[copy error] folder: %s, file name: %s", dir.toFile().getAbsolutePath(), target.getName()));
                        errLogger.error("file copy error", e);
                    }
                }
            } catch (Exception e) {
                System.out.println("[copy error] " + e.getMessage());
                errLogger.error("file copy error ", e);
            }
        }
    }

    private static String getFileExtension(File file) {
        String fileName = file.getName();
        if(fileName.lastIndexOf(".") != -1 && fileName.lastIndexOf(".") != 0)
            return fileName.substring(fileName.lastIndexOf(".")+1);
        else return "";
    }

    private static void processMetaInfoWithHandleException(File file, MediaType mediaType) {
        try {
            processMetaInfo(file, mediaType);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println(String.format("error file path :  %s", file.getAbsolutePath()));
            errLogger.error(String.format("error file path :  %s", file.getAbsolutePath()));
            errLogger.error("processMetaInfoWithHandleException", e);
        }
    }

    private static void processMetaInfo(File file, MediaType mediaType) throws ImageProcessingException, IOException, ParseException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        Iterable<Directory> directories = metadata.getDirectories();
        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        MetaInfo metaInfo = new MetaInfo();
        metaInfo.setFile(file);
        metaInfo.setMediaType(mediaType);
        if(MediaType.Image == mediaType) {
            for (Directory directory : directories) {
                for (Tag tag : directory.getTags()) {
                    if(tag.getTagName().equals("Model")) {
                        metaInfo.setModel(tag.getDescription());
                    } else if(tag.getTagName().equals("Date/Time")) {
                        Date created = inputFormat.parse(tag.getDescription());
                        metaInfo.setCreated(created);
                    }
                }
            }
        } else if(MediaType.Video == mediaType) {
            Mp4Directory mp4Directory = metadata.getFirstDirectoryOfType(Mp4Directory.class);
            Date date = mp4Directory.getDate(Mp4Directory.TAG_CREATION_TIME);
            if(date == null) {
                mp4Directory = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
                date = mp4Directory.getDate(Mp4VideoDirectory.TAG_CREATION_TIME);
                if(date == null) {
                    date = new Date(0L);
                }
            }
            metaInfo.setCreated(date);
        }


        if(metaInfo.getCreated() == null) {
            metaInfo.setCreated(new Date(0L));
        }
        byteBuffer.clear();
        FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        fileChannel.read(byteBuffer);
        String md5Hex = DigestUtils.md5Hex(byteBuffer.array());

        if(!duplicateSet.contains(md5Hex)) {
            duplicateSet.add(md5Hex);
            putInfo(metaInfo, mediaType);
        }
    }

    private static void putInfo(MetaInfo metaInfo, MediaType mediaType) {
        SimpleDateFormat outFormat = new SimpleDateFormat("yyyyMMdd");
        String key;
        if(MediaType.Image == mediaType) {
            key = mediaType.getName() + File.separator + metaInfo.getModel() + File.separator + outFormat.format(metaInfo.getCreated());
        } else {
            key = mediaType.getName() + File.separator + outFormat.format(metaInfo.getCreated());
        }
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(metaInfo);
    }

    private static void walkFiles(File f) {
        if(f.isDirectory()) {
            File[] files = f.listFiles();
            for (File file : files) {
                walkFiles(file);
            }
        } else {
            try {
                index++;
                if(index % 1000 == 0) {
                    System.out.println(">>> processing count " + index);
                }
                Optional<String> contentTypeOpt = Optional.ofNullable(Files.probeContentType(f.toPath()));
                contentTypeOpt.ifPresent(x -> {
                   if(x.startsWith("image") ) {
                        processMetaInfoWithHandleException(f, MediaType.Image);
                    } else if(x.startsWith("video")) {
                       processMetaInfoWithHandleException(f, MediaType.Video);
                   }
                });
            } catch (Exception e) {
                errLogger.error(String.format("[error when walk files] %sn", e.getMessage()));
            }
        }
    }
}

@Data
class MetaInfo {
    private String model;
    private Date created;
    private File file;
    private MediaType mediaType;
    public String getModel() {
        if(model == null) return "";
        return model.replaceAll("[<,>,:,\",\\/,\\\\,|,?,*]", "").trim();
    }

}

@FunctionalInterface
interface FacadConsumer {
    void run() throws Exception;

    default void run(String stageName) {
        long startTime = System.currentTimeMillis();
        try {
            run();
        } catch(Exception e) {
            System.out.println(String.format("[error %s] %s", stageName, e.getMessage()));
        }
        long endTime = System.currentTimeMillis();
        long totalTime =  endTime - startTime;
        System.out.println(String.format("[elapsed time] %s : %d(ms)", stageName, totalTime));
    }
}

enum MediaType {
    Image("image"), Video("video");
    private final String name;
    MediaType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }


}
