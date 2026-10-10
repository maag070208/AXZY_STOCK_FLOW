package dev.axzy.stock.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Startup. This module is the ONLY one that knows all the layers: the adapters are created here,
 * they are passed to the use cases and HTTP is configured. Spring only scans `dev.axzy.stock.app`;
 * nothing from the other layers is registered just by carrying an annotation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class StockApplication

fun main(args: Array<String>) {
    runApplication<StockApplication>(*args)
}
