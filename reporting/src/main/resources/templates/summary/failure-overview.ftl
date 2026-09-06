                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <h3 style="color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;">Test Failure Overview</h3>
                        </td>
                    </tr>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <table style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;">
                                        <table class="failure-scoreboard" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border-style:solid;border-width:1px;border-color:#acb1b9;">
                                            <tr>
                                                <th colspan="2" style="text-align:left;">Most Frequent Failures</th>
                                            </tr>
<#list frequentFailures as f>
                                            <tr class="for-failure" style="color:#f44336;">
                                                <td width="100%" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;" class="frequent-failure for-error">${f.name}</td>
                                                <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;"><span class='count-badge for-failure' style="color:#f44336;">${f.count?c}</span></td>
                                            </tr>
</#list>
                                        </table>
                                    </td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;">
                                        <table class="failure-scoreboard" style="border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;border-style:solid;border-width:1px;border-color:#acb1b9;">
                                            <tr>
                                                <th style="text-align:left;">Most Unstable Features</th>
                                                <th style="text-align:left;">Fails</th>
                                            </tr>
<#list unstableFeatures as u>
                                            <tr class="for-failure" style="color:#f44336;">
                                                <td class="unstable-feature" width="100%" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;">${u.name}</td>
                                                <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;"><span class='count-badge for-failure' style="color:#f44336;">${u.count?c}</span></td>
                                            </tr>
</#list>
                                        </table>
                                    </td>
                                </tr>
                            </table>
${pieChart}                        </td>
                    </tr>
