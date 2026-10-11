param(
    [int]$Port = 18765
)

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Output "XHS profile bridge listening on 127.0.0.1:$Port"

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            if ($context.Request.Url.AbsolutePath -ne '/profile') {
                $context.Response.StatusCode = 404
                $context.Response.Close()
                continue
            }
            $target = $context.Request.QueryString['url']
            if ([string]::IsNullOrWhiteSpace($target)) {
                $context.Response.StatusCode = 400
                $context.Response.Close()
                continue
            }
            $page = Invoke-WebRequest -Uri $target -MaximumRedirection 10 -UseBasicParsing
            $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$page.Content)
            $context.Response.StatusCode = 200
            $context.Response.ContentType = 'text/html; charset=utf-8'
            $context.Response.ContentLength64 = $bytes.Length
            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $context.Response.OutputStream.Close()
        }
        catch {
            $message = [System.Text.Encoding]::UTF8.GetBytes($_.Exception.Message)
            $context.Response.StatusCode = 502
            $context.Response.ContentLength64 = $message.Length
            $context.Response.OutputStream.Write($message, 0, $message.Length)
            $context.Response.OutputStream.Close()
        }
    }
}
finally {
    $listener.Stop()
    $listener.Close()
}
