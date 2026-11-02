package gg.grounds;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;
import software.amazon.awssdk.checksums.SdkChecksum;
import software.amazon.awssdk.checksums.internal.CrcCombineOnMarkChecksum;
import software.amazon.awssdk.checksums.internal.SdkCrc32CChecksum;
import software.amazon.awssdk.checksums.spi.ChecksumAlgorithm;

/**
 * Keep the SDK's existing CRT-absent behavior without linking unavailable optional JNI types.
 * Follows Quarkus Amazon Services' CrtSubstitutions; ordinary checksums remain enabled.
 * https://github.com/quarkiverse/quarkus-amazon-services/blob/7d07c595c59f536843e53514075ca4162152f9f1/common/runtime/src/main/java/io/quarkiverse/amazon/common/runtime/CrtSubstitutions.java
 */
@TargetClass(
        className = "software.amazon.awssdk.checksums.internal.ChecksumProvider",
        onlyWith = NativeChecksumProvider.CrtAbsent.class)
final class NativeChecksumProvider {
    @Substitute
    public static SdkChecksum crc32cImplementation() {
        return new CrcCombineOnMarkChecksum(new CRC32C(), SdkCrc32CChecksum::combine);
    }

    @Substitute
    static SdkChecksum createCrtCrc32C() {
        return null;
    }

    @Substitute
    static SdkChecksum crc64NvmeCrtImplementation() {
        throw new RuntimeException(
                "Could not load software.amazon.awssdk.crt.checksums.CRC64NVME. Add dependency on "
                        + "'software.amazon.awssdk.crt:aws-crt' module to enable CRC64NVME feature.");
    }

    @Substitute
    static SdkChecksum crtXxHash(ChecksumAlgorithm algorithm) {
        throw new RuntimeException(
                "Could not load software.amazon.awssdk.crt.checksums.XXHash for algorithm: "
                        + algorithm.algorithmId()
                        + ". Add dependency on 'software.amazon.awssdk.crt:aws-crt' module.");
    }

    public static final class CrtAbsent implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            try {
                Class.forName(
                        "software.amazon.awssdk.crt.checksums.CRC32C",
                        false,
                        Thread.currentThread().getContextClassLoader());
                return false;
            } catch (ClassNotFoundException absent) {
                return true;
            }
        }
    }
}
