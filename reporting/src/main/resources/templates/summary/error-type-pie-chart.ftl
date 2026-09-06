                            <div style="margin-top:20px;text-align:center;">
                                <h4 style="margin:0 0 12px 0;font-size:16px;color:#333;">Failure Analysis</h4>
<#if single>
                                <div style="display:inline-block;width:${pieSize?c}px;height:${pieSize?c}px;border-radius:50%;background:${singleColor};box-shadow:0 2px 8px rgba(0,0,0,0.15);position:relative;">
                                    <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;">
                                        <div style="font-size:28px;font-weight:bold;color:#fff;">${singlePct}%</div>
                                    </div>
                                </div>
<#else>
                                <div style="display:inline-block;width:${pieSize?c}px;height:${pieSize?c}px;border-radius:50%;background:conic-gradient(from -90deg, ${gradient});box-shadow:0 2px 8px rgba(0,0,0,0.15);margin:0 auto;"></div>
                                <div style="margin-top:16px;padding:12px;background:#f5f5f5;border-radius:8px;">                                    <div style="display:flex;flex-wrap:wrap;justify-content:center;gap:16px;"><#rt>
<#list legend as leg>
                                        <div style="display:flex;align-items:center;gap:6px;">
                                            <span style="display:inline-block;width:12px;height:12px;background:${leg.color};border-radius:2px;"></span>                                            <span style="font-size:12px;color:#333;">${leg.name}: ${leg.pct}</span>                                        </div>
</#list>
                                    </div>                                </div>                            </div>
</#if>
                            </div>
