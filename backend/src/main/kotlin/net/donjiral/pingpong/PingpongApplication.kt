package net.donjiral.pingpong

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PingpongApplication

fun main(args: Array<String>) {
    runApplication<PingpongApplication>(*args)
}
