package tr.borsatakip.v5.data

/**
 * Arama kutusunun tamamen boş kalmaması için kullanılan yerleşik başlangıç dizini.
 * Bu liste piyasa verisi değildir, aktif BIST üyeliği iddiası taşımaz ve tarama evreni olarak kullanılmaz.
 */
object BistBootstrapCatalog {
    /** Takip listesi tamamen boşsa kullanılan, tarama evreni sayılmayan başlangıç listesi. */
    val defaultWatchlist: List<String> = listOf(
        "AKBNK","ASELS","BIMAS","EREGL","FROTO","GARAN","ISCTR","KCHOL","MGROS","PETKM",
        "PGSUS","SAHOL","SASA","SISE","TCELL","THYAO","TOASO","TUPRS","VAKBN","YKBNK"
    )

    val symbols: List<String> = listOf(
        "AKBNK","ALARK","ARCLK","ASELS","ASTOR","BIMAS","DOAS","EKGYO","ENKAI","EREGL",
        "FROTO","GARAN","GUBRF","HEKTS","ISCTR","KCHOL","KOZAA","KOZAL","KRDMD","MGROS",
        "OYAKC","PETKM","PGSUS","SAHOL","SASA","SISE","TCELL","THYAO","TOASO","TUPRS",
        "ULKER","YKBNK","AEFES","AGHOL","AKSA","AKSEN","ALFAS","ANHYT","ANSGR","BERA",
        "BRSAN","CIMSA","CWENE","DOHOL","ECILC","EGEEN","ENJSA","GESAN","GLYHO","GWIND",
        "HALKB","ISGYO","ISMEN","KARTN","KARSN","KCAER","KLSER","KONTR","MAVI","MPARK",
        "ODAS","OTKAR","QUAGR","SKBNK","SMRTG","SOKM","TABGD","TAVHL","TKFEN","TTKOM",
        "VAKBN","VESBE","VESTL","XU030","XU100"
    ).distinct().sorted()
}
