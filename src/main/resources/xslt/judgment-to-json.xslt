<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:lex="urn:lex:content:1"
    exclude-result-prefixes="xs lex">

    <xsl:output method="text" encoding="UTF-8"/>
    <xsl:template match="/">
        <xsl:variable name="h" select="lex:judgment/lex:header"/>
        <xsl:variable name="b" select="lex:judgment/lex:body"/>
        <xsl:variable name="result" select="
            map {
                'content_id'    : string($h/lex:content_id),
                'title'         : string($h/lex:title),
                'court'         : string($h/lex:court),
                'jurisdiction'  : string($h/lex:jurisdiction),
                'decision_date' : string($h/lex:decision_date),

                'citations' : array {
                    for $c in $h/lex:citations/lex:citation
                    return map { 'type' : string($c/@type), 'value' : string($c) }
                },

                'parties' : array {
                    for $p in $h/lex:parties/lex:party
                    return map { 'role' : string($p/@role), 'name' : string($p) }
                },

                'paragraphs' : array {
                    for $p in $b/lex:section/lex:p
                    return map {
                        'id'      : string($p/@id),
                        'section' : string($p/parent::lex:section/@type),
                        'text'    : string($p)
                    }
                },

                'full_text' : string-join(
                    for $p in $b/lex:section/lex:p
                    return normalize-space(string($p)),
                    ' '
                )
            }
        "/>
        <xsl:value-of select="serialize($result, map { 'method' : 'json', 'indent' : true() })"/>
    </xsl:template>

</xsl:stylesheet>
