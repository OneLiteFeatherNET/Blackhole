package net.onelitefeather.blackhole

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import net.onelitefeather.blackhole.spec.Spec
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@EnableMongoRepositories(
    basePackageClasses = [Spec::class]
)
@SpringBootApplication(
    scanBasePackageClasses = [
        Spec::class,
        BlackholeBackendApplication::class,
    ]
)
class BlackholeBackendApplication

fun main(args: Array<String>) {
    runApplication<BlackholeBackendApplication>(*args)
}


@Bean
fun objectMapperBuilder(): Jackson2ObjectMapperBuilder = Jackson2ObjectMapperBuilder().modulesToInstall(KotlinModule.Builder().build()).modulesToInstall(JavaTimeModule())
