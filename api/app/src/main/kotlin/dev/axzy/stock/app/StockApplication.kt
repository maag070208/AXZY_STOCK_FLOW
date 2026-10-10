package dev.axzy.stock.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * El arranque. Este módulo es el ÚNICO que conoce todas las capas: aquí se crean los adaptadores,
 * se les pasan a los casos de uso y se configura HTTP. Spring solo explora `dev.axzy.stock.app`;
 * nada de las demás capas se registra solo por llevar una anotación.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class StockApplication

fun main(args: Array<String>) {
    runApplication<StockApplication>(*args)
}
