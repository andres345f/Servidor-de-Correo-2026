package com.feder.presentacion.respuesta.exportacion;

import com.feder.modelo.FormatoReporte;

import java.util.Objects;

/** Elige el exportador según el formato que pidió el usuario en el comando del reporte. */
public final class FabricaExportadores {

    private FabricaExportadores() {
    }

    public static ExportadorReporte para(FormatoReporte formato) {
        switch (Objects.requireNonNull(formato, "formato")) {
            case EXCEL:
                return new ExportadorExcel();
            case PDF:
                return new ExportadorPdf();
            default:
                throw new IllegalArgumentException("Formato de reporte no contemplado: " + formato);
        }
    }
}
