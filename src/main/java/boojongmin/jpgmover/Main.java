package boojongmin.jpgmover;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    static Map<String, List<MetaInfo>> result = new HashMap<>();
    static File source;
    static File destination;
    static Set<String> duplicateSet = new HashSet<>();
    public static void main(String[] args) throws Exception {
        elapsedTimeChecker(() -> {
            checkInput(args);
            walkFiles(source);
            printReport();
        }, "1. collect file info" );

        elapsedTimeChecker(() -> {
            moveJpg();
        }, "2. move files");

        System.out.println("complete");
    }

    private static void elapsedTimeChecker(FacadConsumer fn, String stageName) throws Exception {
        System.out.printf("start -> %s\n", stageName);
        long startTime = System.currentTimeMillis();
        fn.run(stageName);
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.printf("end -> %s, elapsed time %d(ms)\n", stageName, totalTime);
    }


    private static void printReport() {
        System.out.printf("total image count is %d\n", result.size());
        System.out.printf("total image size is %.2f Mb\n",
            result.values().stream().map(x ->
                x.stream()
                    .map(y -> y.getFile().length())
                    .reduce(0L, Long::sum)
            )
            .reduce(0L, Long::sum)
            .doubleValue() / (1000 * 1000)
        )
        ;
    }

    private static void checkInput(String[] args) {
        if(args.length != 2) {
            System.out.println("[error] your input size is not 2.\nexit\n");
            System.exit(0);
        }
        source = new File(args[0]);
        destination = new File(args[1]);
        if(!source.exists()) {
            System.out.println("first argument is wrong. check image path");
        }

    }

    private static void moveJpg() throws URISyntaxException, IOException {
        if(!destination.exists()) {
            destination.mkdirs();
        }
        Path path = Paths.get(destination.toURI());

        for (String key : result.keySet()) {
            Path dir = path.resolve(key);
            File file = dir.toFile();
            if(!file.exists()) {
                file.mkdirs();
            }
            List<MetaInfo> infos = result.get(key);
            for (MetaInfo info : infos) {
                File target = info.getFile();
                Files.copy(Paths.get(target.toURI()), dir.resolve(target.getName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void processMetaInfo(File file) throws ImageProcessingException, IOException, ParseException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        Collection<ExifIFD0Directory> exifIFD0Directories = metadata.getDirectoriesOfType(ExifIFD0Directory.class);
        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        MetaInfo metaInfo = new MetaInfo();
        metaInfo.setFile(file);
        for (ExifIFD0Directory directory : exifIFD0Directories) {
            for (Tag tag : directory.getTags()) {
                if(tag.getTagName().equals("Model")) {
                    metaInfo.setModel(tag.getDescription());
                } else if(tag.getTagName().equals("Date/Time")) {
                    Date created = inputFormat.parse(tag.getDescription());
                    metaInfo.setCreated(created);
                }
            }
        }
        String sha256Hex = DigestUtils.sha256Hex(Files.readAllBytes(file.toPath()));
        if(!duplicateSet.contains(sha256Hex)) {
            duplicateSet.add(sha256Hex);
            putInfo(metaInfo);
        }
    }

    private static void processMetaInfoWithHandleException(File file) {
        try {
            processMetaInfo(file);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private static void putInfo(MetaInfo metaInfo) {
        SimpleDateFormat outFormat = new SimpleDateFormat("yyyyMMdd");
        String key = metaInfo.getModel() + File.separator + outFormat.format(metaInfo.getCreated());
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(metaInfo);
    }

    private static void walkFiles(File f) throws IOException {
        if(f.isDirectory()) {
            File[] files = f.listFiles();
            for (File file : files) {
                walkFiles(file);
            }
        } else {
            Optional<String> contentTypeOpt = Optional.ofNullable(Files.probeContentType(f.toPath()));
            contentTypeOpt.ifPresent(x -> {
               if(x.startsWith("image")) processMetaInfoWithHandleException(f);
            });
        }
    }
}

@Data
class MetaInfo {
    private String model;
    private Date created;
    private File file;
}

@FunctionalInterface
interface FacadConsumer {
    void run() throws Exception;

    default void run(String stageName) throws Exception {
        long startTime = System.currentTimeMillis();
        run();
        long endTime = System.currentTimeMillis();
        long totalTime =  endTime - startTime;
        System.out.printf("[elapsed time] %s : %d(ms)\n", stageName, totalTime);
    }
}
