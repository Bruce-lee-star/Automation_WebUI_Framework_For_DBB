<#if hasFailures>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <h3 style="color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;">Full Failure List</h3>
                            <table class="failure-list failure-scoreboard" style="border-width:1px;border-style:solid;border-color:#dee2e6;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                <tr>
                                    <th style="text-align:left;width:50%;padding:10px 12px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;">Requirement</th>
                                    <th style="text-align:left;width:50%;padding:10px 12px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;">Failure</th>
                                </tr>
<#list failureGroups as g>
                                <tr>
                                    <td colspan="2" class="feature" style="font-family:Helvetica, sans-serif;font-size:14px;font-weight:600;vertical-align:top;padding:8px 16px;background-color:#f0f4f8;border-bottom:2px solid #dee2e6;color:#3d5a80;">${g.feature}</td>
                                </tr>
<#list g.scenarios as s>
                                <tr>
                                    <td class="scenarioName" style="font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 24px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;">
                                        <a href="${s.link}" target="_blank" style="color:#0066cc;text-decoration:none;">${s.name}</a>
                                    </td>
                                    <td class="scenarioResult" style="font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 12px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;">
<span style="color:${s.labelColor};font-size:12px;font-weight:bold;text-transform:uppercase;">${s.labelText}</span><#if s.hasError><div style="margin-top:4px;padding-left:1em;color:${s.color};font-size:11px;line-height:1.3;word-break:break-all;">${s.error}</div>
</#if>                                    </td>
                                </tr>
</#list>
</#list>
                            </table>
                        </td>
                    </tr>
</#if>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:4px;padding-bottom:4px;">
                            <div style="text-align:center;">
                                <h3 style="color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;display:inline;">Full Test Results</h3>
                                <a style="text-transform:uppercase;color:#ffffff;text-decoration:none;font-weight:bold;padding:0.3em 0.8em;background:#5FB0E0;border-radius:4px;font-size:12px;margin-left:15px;" href="${csvLink}" target="_blank">Download CSV</a>
                            </div>
                            <table class="failure-list failure-scoreboard" style="border-width:1px;border-style:solid;border-color:#dee2e6;border-collapse:separate;mso-table-lspace:0pt;mso-table-rspace:0pt;width:100%;">
                                <tr>
                                    <th style="text-align:left;width:50%;padding:12px 16px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;">Requirement</th>
                                    <th style="text-align:left;width:50%;padding:12px 16px;background:linear-gradient(180deg,#f8f9fa 0%,#e9ecef 100%);font-weight:600;font-size:13px;color:#495057;">Result</th>
                                </tr>
<#list resultGroups as g>
                                <tr>
                                    <td colspan="2" class="feature feature-title" style="font-family:Helvetica, sans-serif;font-size:14px;font-weight:600;vertical-align:top;padding:8px 16px;background-color:#f0f4f8;border-bottom:2px solid #dee2e6;color:#3d5a80;">${g.feature}</td>
                                </tr>
<#list g.rows as s>
                                <tr>
                                    <td class="scenarioName" style="font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 24px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;">
                                        <a href="${s.link}" target="_blank" style="color:#0066cc;text-decoration:none;">${s.name}</a>
                                    </td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:13px;vertical-align:top;padding:10px 12px;width:50%;word-wrap:break-word;overflow-wrap:break-word;border-bottom:1px solid #eee;">
<span style="color:${s.labelColor};font-size:12px;font-weight:bold;text-transform:uppercase;">${s.labelText}</span><#if s.hasError><div style="margin-top:4px;padding-left:1em;color:${s.color};font-size:11px;line-height:1.3;word-break:break-all;">${s.error}</div>
</#if>                                    </td>
                                </tr>
</#list>
</#list>
                            </table>
                        </td>
                    </tr>
