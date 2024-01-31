package util;

import android.util.LongArray;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;

public class FileUtils {
    public static final int NOT_FOUND = -1;
    public static final int SAVE_FILE_START_INDEX = 1;

    public static String getLastFileName(File file, boolean withExtension) {
        if (file == null) {
            return null;
        }
        return getLastFileName(file.getName(), withExtension);
    }

    public static String getLastFileName(String fileName, boolean withExtension) {
        if (fileName == null) {
            return null;
        }
        if (!withExtension) {
            int dotIndex = fileName.lastIndexOf(".");
            int pathSegmentIndex = fileName.lastIndexOf(File.separator) + 1;
            if (dotIndex == NOT_FOUND) {
                dotIndex = fileName.length();
            }
            if (pathSegmentIndex == NOT_FOUND) {
                pathSegmentIndex = 0;
            }
            fileName = fileName.substring(pathSegmentIndex, dotIndex);
        }
        return fileName;
    }

    public static String getFileExtension(File file, boolean withDot) {
        if (file == null) {
            return null;
        }
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex == NOT_FOUND) {
            return null;
        }
        if (withDot) {
            return fileName.substring(dotIndex, fileName.length());
        }
        return fileName.substring(dotIndex + 1, fileName.length());
    }

    public static File renameFile(File file, String newName) {
        if (file == null) {
            return null;
        }
        String filePath = file.getAbsolutePath();
        String folderPath = file.getParent();
        String extension = filePath.substring(filePath.lastIndexOf("."), filePath.length());
        File newFile = new File(folderPath, newName + extension);
        if (file.renameTo(newFile)) {
            return newFile;
        }
        return file;
    }

    public static boolean exists(File file) {
        return file != null && file.exists();
    }

    public static long getSuitableIndexOfRecording(String prefix) {
        long returnIndex = SAVE_FILE_START_INDEX;
        File file = new File(StorageUtils.getPhoneStoragePath());
        File list[] = file.listFiles();
        LongArray array = new LongArray();
        if (list != null && list.length != 0) {
            for (File item : list) {
                String name = getLastFileName(item, false);
                if (name.startsWith(prefix)) {
                    int index = prefix.length();
                    String numString = name.substring(index, name.length());
                    try {
                        array.add(Long.parseLong(numString));
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        int size = array.size();
        for (int i = 0; i < size; i++) {
            if (array.indexOf(returnIndex) >= 0) {
                returnIndex++;
            }
        }

        return returnIndex;
    }

    public static boolean isFolderEmpty(String filePath) {
        File file = new File(filePath);
        File[] files = file.listFiles();
        return files == null || files.length == 0;
    }

    public static boolean deleteFile(File file, Context context) {
        boolean ret = false;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (File f : files) {
                // remove all files in folder.
                deleteFile(f, context);
            }
        }
        if (file.delete()) {
            // update database.
            DatabaseUtils.delete(context, file);
            ret = true;
        }
        return ret;
    }

    public static ArrayList<Uri> urisFromFolder(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return null;
        }

        ArrayList<Uri> uris = new ArrayList<Uri>();
        File[] list = folder.listFiles();
        for (File file : list) {
            uris.add(Uri.fromFile(file));
        }
        return uris;
    }
}
