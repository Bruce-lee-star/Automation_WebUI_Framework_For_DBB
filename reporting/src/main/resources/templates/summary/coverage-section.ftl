                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <h3 style="color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;">Functional Coverage</h3>
                        </td>
                    </tr>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <h4 class="tag-title" style="color:#222222;font-family:Helvetica, sans-serif;line-height:1.4;margin:0;font-weight:500;font-size:18px;margin-top:20px;text-transform:capitalize;">Feature</h4>
                            <table class="test-results-table categories" style="border-collapse:collapse;width:100%;border:1px solid grey;margin-bottom:26px;table-layout:fixed;">
                                <tr>
                                    <th width="50%" style="text-align:left;white-space:nowrap;">Category</th>
                                    <th width="8%" style="text-align:left;white-space:nowrap;">Tests</th>
                                    <th width="8%" style="text-align:left;white-space:nowrap;">Pass</th>
                                    <th width="34%" style="text-align:left;white-space:nowrap;">Results</th>
                                </tr>
<#list features as f>
                                <tr>
                                    <td class="tag-subtitle categories" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;text-transform:capitalize;border:0.5px solid #dddddd;word-wrap:break-word;overflow-wrap:break-word;"><a href="${f.link}" target="_blank">${f.name}</a></td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;text-align:center;border:0.5px solid #dddddd;">${f.total?c}</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;text-align:center;border:0.5px solid #dddddd;">${f.passPercent?c}%</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;">
                                        <table cellspacing="0" cellpadding="0" class="result-bar" width="100%" style="border-collapse:collapse;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                            <tr>
<#if f.allPass>
                                                <td class="success-background" title="All ${f.passed?c} tests passed (100%)" width="100%" style="font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#52B255;color:white;text-align:center;font-size:0.9em;padding:4px;"><span>100%</span></td>
<#else>
<#if f.passWidth gt 0>
                                                <td class="success-background" title="${f.passed?c} passing tests (${f.passWidth?c}%)" width="${f.passWidth?c}%" style="font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#52B255;color:white;text-align:center;font-size:0.9em;padding:4px;"><span>${f.passed?c}</span></td>
</#if>
<#if f.failWidth gt 0>
                                                <td class="failure-background" title="${f.failed?c} failing tests (${f.failWidth?c}%)" width="${f.failWidth?c}%" style="font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#f44336;color:white;text-align:center;font-size:0.9em;padding:4px;"><span>${f.failed?c}</span></td>
</#if>
<#if f.errorWidth gt 0>
                                                <td class="error-background" title="${f.error?c} broken tests (${f.errorWidth?c}%)" width="${f.errorWidth?c}%" style="font-family:Helvetica, sans-serif;vertical-align:middle;background-color:#ECA43A;color:white;text-align:center;font-size:0.9em;padding:4px;"><span>${f.error?c}</span></td>
</#if>
</#if>
                                            </tr>
                                        </table>
                                    </td>
                                </tr>
</#list>
                            </table>
                        </td>
                    </tr>
