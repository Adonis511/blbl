<#
.SYNOPSIS
  本地验证 B 站「收藏夹 list-all + 合集 type=21」（必须带浏览器登录 Cookie，无 Cookie 无法验证合集列表）。

--- 环境变量写在哪、管多久 ---
  本脚本读取：当前 PowerShell 会话里的 $env:BILI_COOKIE，或参数 -CookieHeader。
  - 只在「当前窗口」有效（关掉窗口就没了）：
      $env:BILI_COOKIE = '粘贴整段 Cookie'
  - 想长期保存在用户环境（可选，有泄露风险）：
      Win+R → sysdm.cpl → 高级 → 环境变量 → 用户变量 → 新建
      变量名 BILI_COOKIE，变量值粘贴 Cookie → 确定后新开一个 PowerShell 才生效。
  不要把 Cookie 写进仓库、不要发给不可信的人；验证完可在同窗口执行：
      Remove-Item Env:BILI_COOKIE

--- 浏览器里怎么复制 Cookie（Chrome / Edge 通用）---
  1. 用浏览器登录 www.bilibili.com（确保已登录你的账号）。
  2. 按 F12 打开开发者工具 → 切到「网络 / Network」。
  3. 勾选「保留日志」后刷新页面 (F5)，或在地址栏打开任意视频页再刷新。
  4. 在 Network 筛选框输入：api.bilibili.com 或 list-all
  5. 点选一条发往 api.bilibili.com 的请求（例如包含 fav、nav、player 等均可，只要请求头里有 Cookie）。
  6. 右侧找到「请求标头 / Request Headers」→ 找到 Cookie: 后面很长一串。
  7. 只复制「值」整段（从 SESSDATA= 或 buvid3= 开始一直到分号分隔的多个键值对），不要带「Cookie:」前缀。
     建议整段复制，至少包含 SESSDATA；有 bili_jct、DedeUserID 等同浏览器会话更稳。

--- 一键命令（推荐：先 cd 到本脚本所在目录）---
  cd E:\Android\blbl\tools\bili-api-verify
  $env:BILI_COOKIE = '这里粘贴整段 Cookie'
  .\verify-fav-season.ps1 -Mid 9999137

  单行（不换行执行时把 Cookie 换成你的；Mid 换成你的 UID）：
  cd E:\Android\blbl\tools\bili-api-verify; $env:BILI_COOKIE='整段Cookie'; .\verify-fav-season.ps1 -Mid 9999137

  不用环境变量、一次性写在参数里（注意整段用单引号包起来，内部不要有单引号）：
  .\verify-fav-season.ps1 -Mid 9999137 -CookieHeader '整段Cookie'

--- 说明 ---
  无 Cookie 时 list-all 的 data 常为 null，无法验证「合集列表」是否正确；必须以登录 Cookie 跑本脚本为准。
#>

param(
    [Parameter(Mandatory = $true)][long]$Mid,
    [string]$CookieHeader = $env:BILI_COOKIE
)

if ([string]::IsNullOrWhiteSpace($CookieHeader)) {
    Write-Error "请设置 -CookieHeader 或环境变量 BILI_COOKIE（从浏览器 bilibili.com 请求头 Cookie 复制）。不要提交到 git。"
    exit 1
}

$base = "https://api.bilibili.com"
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"

function Get-Json($url) {
    $r = curl.exe -sS --max-time 30 -H "User-Agent: $ua" -H "Referer: https://www.bilibili.com/" -H "Cookie: $CookieHeader" $url
    return ($r | ConvertFrom-Json)
}

$listUrl = "${base}/x/v3/fav/folder/created/list-all?up_mid=$Mid&web_location=333.1387"
Write-Host "== GET list-all ==" $listUrl
$j = Get-Json $listUrl
Write-Host ("code=" + $j.code + " message=" + $j.message)
if ($null -eq $j.data) {
    Write-Host "data=null -> 未带有效登录 Cookie，或账号无 list-all 权限。"
    exit 2
}

$season = $j.data.season
if ($season) {
    Write-Host "data.season:" ($season | ConvertTo-Json -Compress -Depth 5)
}

$list = @($j.data.list)
Write-Host ("list count=" + $list.Count)
foreach ($it in $list | Select-Object -First 30) {
    $tid = $it.id
    if (-not $tid) { $tid = $it.media_id }
    Write-Host ("  id=" + $tid + " media_count=" + $it.media_count + " title=" + $it.title)
}

# 对前 8 个有内容的夹探测 resource/list 是否含 type=21（与 App 逻辑一致）
$n = 0
foreach ($it in $list) {
    if ($n -ge 8) { break }
    $mediaId = $it.id
    if (-not $mediaId) { $mediaId = $it.media_id }
    if (-not $mediaId -or $it.media_count -le 0) { continue }
    $n++
    $u = "${base}/x/v3/fav/resource/list?media_id=$mediaId&pn=1&ps=20&order=mtime&type=0&platform=web"
    $rj = Get-Json $u
    if ($rj.code -ne 0) {
        Write-Host ("resource/list FAIL media_id=" + $mediaId + " code=" + $rj.code + " " + $rj.message)
        continue
    }
    $medias = @($rj.data.medias)
    $c21 = ($medias | Where-Object { $_.type -eq 21 }).Count
    Write-Host ("resource/list media_id=" + $mediaId + " medias=" + $medias.Count + " type21=" + $c21 + " title=" + $it.title)
}

# 与 App「合集」Tab 优先数据源一致：collected/list + platform=web（docs/fav/info.md）
$colUrl = "${base}/x/v3/fav/folder/collected/list?up_mid=$Mid&pn=1&ps=20&platform=web&web_location=333.1387"
Write-Host "== GET collected/list (platform=web) ==" $colUrl
$cj = Get-Json $colUrl
Write-Host ("code=" + $cj.code + " message=" + $cj.message)
if ($null -ne $cj.data) {
    $clist = @($cj.data.list)
    Write-Host ("count=" + $cj.data.count + " list=" + $clist.Count)
    foreach ($it in $clist | Select-Object -First 25) {
        $rid = $it.id
        $um = $it.upper.mid
        if (-not $um) { $um = $it.mid }
        $modEq = (($rid % 100) -eq ($um % 100))
        $kind = if ($modEq) { "likely_folder_mlid" } else { "likely_ugc_season_id" }
        Write-Host ("  id=" + $rid + " upper.mid=" + $um + " " + $kind + " title=" + $it.title)
    }
}

Write-Host "完成。若 list 有夹但 type21=0，说明该夹无「视频合集」条目；Web「收藏-合集」也为空。"
