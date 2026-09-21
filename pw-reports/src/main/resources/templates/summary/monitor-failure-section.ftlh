<#-- API 监控失败与数据丢失 显示区域（仅在 report.hasContent() 时由 Java 渲染；按 owner 分组 + 数据丢失红框）。
     动态值经 .ftlh 的 HTMLOutputFormat 自动转义，避免 request/response body 中的 XSS。 -->
<#if report.owners?has_content>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:8px;padding-bottom:8px;">
                            <h3 style="color:#222222;font-family:Helvetica, sans-serif;font-weight:400;line-height:1.4;margin:0;font-size:20px;text-align:center;">API 监控失败（按 Owner 汇总）</h3>
                        </td>
                    </tr>
<#list report.owners as ownerBlock>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:8px;padding-bottom:12px;">
                            <h4 class="tag-title" style="color:#222222;font-family:Helvetica, sans-serif;line-height:1.4;margin:0;font-weight:500;font-size:18px;margin-top:12px;text-transform:capitalize;">收件人（API Owner）：${ownerBlock.owner!}</h4>
<#list ownerBlock.items as item>
                            <table class="test-results-table" style="border-collapse:collapse;width:100%;border:1px solid grey;margin-bottom:12px;table-layout:fixed;">
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;word-wrap:break-word;overflow-wrap:break-word;">功能</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;word-wrap:break-word;overflow-wrap:break-word;">${item.feature!}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">Endpoint</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;word-break:break-all;"><code>${item.pattern!}</code></td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">状态 / 方法</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;">${item.status!'?'} / ${item.method!}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">URL</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;word-break:break-all;">${item.requestUrl!}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">失败原因</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;word-wrap:break-word;overflow-wrap:break-word;">${item.reason!}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">触发 Scenario</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;word-wrap:break-word;overflow-wrap:break-word;">${(item.scenarios![])?join(", ")}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">Request Body</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;white-space:pre-wrap;font-family:monospace;font-size:12px;word-wrap:break-word;overflow-wrap:break-word;">${item.requestBody!}</td>
                                </tr>
                                <tr>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;width:28%;border:0.5px solid #dddddd;font-weight:600;background:#fafafa;">Response Body</td>
                                    <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #dddddd;white-space:pre-wrap;font-family:monospace;font-size:12px;word-wrap:break-word;overflow-wrap:break-word;">${item.responseBody!}</td>
                                </tr>
                            </table>
</#list>
                        </td>
                    </tr>
</#list>
</#if>
<#if report.dataLossByCategory?has_content>
                    <tr>
                        <td class="compact-wrapper" style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;box-sizing:border-box;padding-left:24px;padding-right:24px;padding-top:8px;padding-bottom:12px;">
                            <div style="border:2px solid #f44336;border-radius:4px;padding:12px;background:#fdecea;">
                                <h4 style="color:#c62828;font-family:Helvetica, sans-serif;line-height:1.4;margin:0 0 8px 0;font-weight:500;font-size:18px;">⚠ 数据完整性告警（API 监控数据丢失）</h4>
                                <p style="font-family:Helvetica, sans-serif;font-size:14px;margin:0 0 8px 0;color:#b71c1c;">本轮 API 监控记录入库失败 / 丢弃共 <strong>${report.totalDataLoss?c}</strong> 条，部分监控数据未落库（数据完整性受损）。</p>
                                <table class="test-results-table" style="border-collapse:collapse;width:100%;border:1px solid #f44336;">
<#list report.dataLossByCategory as cat, count>
                                    <tr>
                                        <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #f44336;word-wrap:break-word;overflow-wrap:break-word;"><code>${cat!}</code></td>
                                        <td style="font-family:Helvetica, sans-serif;font-size:14px;vertical-align:top;border:0.5px solid #f44336;text-align:right;white-space:nowrap;">${count?c} 条</td>
                                    </tr>
</#list>
                                </table>
                                <p style="font-family:Helvetica, sans-serif;font-size:12px;margin:8px 0 0 0;color:#666666;">排查建议：检查 monitor.db.* 配置、数据库可用性、网络连接，以及 Hikari 连接池与批量刷入器日志。</p>
                            </div>
                        </td>
                    </tr>
</#if>
