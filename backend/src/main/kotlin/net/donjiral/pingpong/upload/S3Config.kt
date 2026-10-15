package net.donjiral.pingpong.upload

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@ConfigurationProperties(prefix = "app.s3")
data class S3Properties(
    var enabled: Boolean = false,
    var bucket: String = "",
    var region: String = "ap-southeast-2",
    var keyPrefix: String = "uploads/",
    var publicBaseUrl: String = ""
)

@Configuration
@ConditionalOnProperty(prefix = "app.s3", name = ["enabled"], havingValue = "true")
class S3Config(private val props: S3Properties) {
    // 자격증명은 EC2 인스턴스 역할(IAM Role)에서 자동으로 가져옵니다.
    @Bean
    fun s3Client(): S3Client =
        S3Client.builder().region(Region.of(props.region)).build()
}
