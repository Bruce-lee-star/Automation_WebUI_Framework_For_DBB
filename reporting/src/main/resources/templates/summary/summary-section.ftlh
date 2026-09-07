<#macro legendRow r>
                                                <td class="${r.colorClass} legend-key legend-label" width="30%" style="font-family:Helvetica, sans-serif;vertical-align:top;font-weight:bold;padding-left:4px;font-size:0.9em;white-space:nowrap;border-top:solid 0.5px #DDDDDD;border-bottom:solid 0.5px #DDDDDD;border-left:solid 0.5px #DDDDDD;${r.colorStyle}">${r.label}</td>
                                                <td class="${r.colorClass} legend-result" style="font-family:Helvetica, sans-serif;vertical-align:top;font-weight:bold;font-size:0.9em;padding-left:4px;border-top:solid 0.5px #DDDDDD;border-bottom:solid 0.5px #DDDDDD;border-right:solid 0.5px #DDDDDD;text-align:right;${r.colorStyle}">
                                                    <span class="${r.badgeClass}" style="border-radius:4px;padding:2px 4px;white-space:nowrap;">${r.count?c}</span>
                                                </td>
</#macro>
                    <tr>
                        <td class="wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding:24px;">
                            <table border="0" cellpadding="0" cellspacing="0" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;">
                                        <table cellspacing="0" cellpadding="0" class="summary-bar" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                            <tr>
<#list barCells as c>
<#if c.width gt 0>
                                                <td class="${c.cssClass} summary summary-bar-cell" width="${c.width?c}%" valign="middle" align="center" style="padding:${c.padding};">
<#if c.hasCount>
                                                        <span class="summary" title="${c.count?c} ${c.title}">${c.percent?c}%</span>
</#if>
                                                </td>
<#else>
                                                <td class="${c.cssClass} summary summary-bar-cell" width="0%" valign="middle" align="center" style="padding:0px;"></td>
</#if>
</#list>
                                            </tr>
                                        </table>
                                        <table cellspacing="0" cellpadding="2" class="legend" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border:1px solid #acb1b9;margin-top:20px;">
                                            <tr>
                                                <td class="overview" colspan="6" style="font-family:Helvetica, sans-serif;vertical-align:middle;font-weight:bold;font-size:1.1em;color:#515151;background-color:#EBEBEB;padding:4px 0 4px 5px;"><span>${total?c} test${totalSuffix} executed on</span>
                                                    <span>${execTime}</span>
                                                </td>
                                            </tr>
                                        </table>
                                        <table cellspacing="0" cellpadding="2" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                            <tr>
<#list legendRows1 as r>
<@legendRow r/>
</#list>
                                            </tr>
                                            <tr>
<#list legendRows2 as r>
<@legendRow r/>
</#list>
                                            </tr>
                                        </table>
                                        <table class="timings" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;padding:0 5px;">
                                            <tr>
                                                <th style="color:grey;text-align:right;font-size:0.9em;">Total test execution time</th>
                                                <th style="color:grey;text-align:right;font-size:0.9em;">Total clock time</th>
                                                <th style="color:grey;text-align:right;font-size:0.9em;">Average test execution time</th>
                                                <th style="color:grey;text-align:right;font-size:0.9em;">Max test execution time</th>
                                                <th style="color:grey;text-align:right;font-size:0.9em;">Min test execution time</th>
                                            </tr>
                                            <tr>
                                                <td style="font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;">${timings.total}</td>
                                                <td style="font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;">${timings.clock}</td>
                                                <td style="font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;">${timings.avg}</td>
                                                <td style="font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;">${timings.max}</td>
                                                <td style="font-family:Helvetica, sans-serif;vertical-align:top;color:grey;text-align:right;font-size:0.9em;">${timings.min}</td>
                                            </tr>
                                        </table>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
