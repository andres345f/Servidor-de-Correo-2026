package com.feder.negocio.indicadores;

/** Indicadores de período de CU7 de un mes de la serie mensual (el primero y el último pueden ser parciales). */
public record IndicadoresDelMes(String etiqueta, AnalisisComercial ventas, AnalisisCaja caja, AnalisisCompras compras) {
}
