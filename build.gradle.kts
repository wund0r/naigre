import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    id("com.android.application") version "9.3.1" apply false
}

tasks.wrapper {
    gradleVersion = "9.5.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
}
