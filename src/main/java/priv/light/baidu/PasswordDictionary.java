package priv.light.baidu;

import lombok.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Light
 * @date 2022/3/30 13:53
 */

public class PasswordDictionary {

    private static final char[] BAI_DU_PASSWORD_INPUT = "qwertyuiopasdfghjklzxcvbnm9876543210".toCharArray();

    private final int count;
    private final File targetFile;
    private FileOutputStream outputStream;
    private FileOutputStream appendOutputStream;

    public PasswordDictionary(int count, @NonNull File targetFile) {
        this.count = count;
        this.targetFile = targetFile;
    }

    private static void repeatableCombine(int count, int depth, StringBuilder result, Set<String> results) {
        if (depth == count) {
            results.add(result.toString());
            return;
        }

        for (char c : BAI_DU_PASSWORD_INPUT) {
            result.append(c);
            repeatableCombine(count, depth + 1, result, results);
            result.deleteCharAt(result.length() - 1);
        }
    }

    private static void arrange(char[] input, int count, boolean[] hasUsed, int depth, StringBuilder result, Set<String> results) {
        if (depth == count) {
            results.add(result.toString());
            return;
        }

        for (int i = 0; i < input.length; i++) {
            if (hasUsed[i]) {
                continue;
            }

            result.append(input[i]);
            hasUsed[i] = true;
            arrange(input, count, hasUsed, depth + 1, result, results);
            result.deleteCharAt(result.length() - 1);
            hasUsed[i] = false;
        }
    }

    public void writeToFile() throws IOException {
        StringBuilder combineResult = new StringBuilder();
        Set<String> allCombineResult = new HashSet<>();

        repeatableCombine(count, 0, combineResult, allCombineResult);

        StringBuilder arrangeResult = new StringBuilder();
        Set<String> allArrangeResult = new HashSet<>();
        boolean[] hasUsed = new boolean[BAI_DU_PASSWORD_INPUT.length];

        for (String password : allCombineResult) {
            if (password.matches("^(\\d|[a-z])\\1{3}$")) {
                continue;
            }

            arrange(password.toCharArray(), count, hasUsed, 0, arrangeResult, allArrangeResult);
        }

        this.write(allArrangeResult, false);
    }

    private void write(Set<String> allArrangeResult, boolean append) throws IOException {
        FileOutputStream fileOutputStream;

        if (!append && this.outputStream == null) {
            this.outputStream = new FileOutputStream(this.targetFile);
            fileOutputStream = this.outputStream;
        } else {
            if (append && this.appendOutputStream == null) {
                this.appendOutputStream = new FileOutputStream(this.targetFile, true);
                fileOutputStream = this.appendOutputStream;
            } else {
                fileOutputStream = append ? this.appendOutputStream : this.outputStream;
            }
        }

        try {
            FileChannel channel = fileOutputStream.getChannel();
            FileLock lock = channel.lock();
            if (lock != null) {
                for (String password : allArrangeResult) {
                    String format = String.format("%s%s", password, System.lineSeparator());
                    channel.write(ByteBuffer.wrap(format.getBytes(StandardCharsets.UTF_8)));
                }

                channel.force(true);
                lock.release();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Set<String> readPassword() {
        Set<String> result = new HashSet<>();
        try (final FileInputStream fileInputStream = new FileInputStream(this.targetFile)) {
            FileChannel channel = fileInputStream.getChannel();
            FileLock lock = channel.lock(0, Long.MAX_VALUE, true);
            if (lock != null) {
                int lineSeparatorLength = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
                ByteBuffer byteBuffer = ByteBuffer.allocate(this.count + lineSeparatorLength);
                while (channel.read(byteBuffer) != -1) {
                    String password = new String(Arrays.copyOfRange(byteBuffer.array(), 0, this.count), StandardCharsets.UTF_8);
                    result.add(password);
                    byteBuffer.clear();
                }

                lock.release();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    public void appendPassword(@NonNull Set<String> newPassword) throws IOException {
        this.write(newPassword, true);
    }

    public void dispose() {
        if (this.outputStream != null) {
            try {
                this.outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (this.appendOutputStream != null) {
            try {
                this.appendOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
