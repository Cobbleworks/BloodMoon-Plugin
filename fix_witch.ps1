$encoding = [System.Text.UTF8Encoding]::new($false)
$file = "C:\Users\bernd\Desktop\Projects\Cobbleworks-Repos\BloodMoon-Plugin\src\main\java\com\yourname\bloodmoon\mobs\WitchNPC.java"
$c = [System.IO.File]::ReadAllText($file)

# 1. Remove decay plague line after handleSentinelAttack target assignment
$before = $c.Length

# Find and remove both decay plague lines
$c = [regex]::Replace($c, '\r?\n[ \t]+plugin\.getDecayPlagueEffect\(\)\.applyStack\(player\);', '')

Write-Host ("Removed decay plague: " + ($before - $c.Length) + " chars removed")

# 2. Add warpCooldown field if missing
if ($c -notmatch 'private int warpCooldown') {
    $c = $c.Replace(
        "    private boolean brandActive = false;",
        "    private boolean brandActive = false;`r`n    private int warpCooldown = 0;"
    )
    Write-Host "Added warpCooldown field"
} else {
    Write-Host "warpCooldown already present"
}

# 3. Add warpCooldown-- in tick() if missing
if ($c -notmatch 'warpCooldown--') {
    $c = $c.Replace(
        "        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));",
        "        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));`r`n        if (warpCooldown > 0) warpCooldown--;"
    )
    Write-Host "Added warpCooldown-- in tick"
} else {
    Write-Host "warpCooldown-- already present"
}

# 4. Add warp trigger before navigator setTarget if missing
if ($c -notmatch 'warpCooldown <= 0') {
    $c = $c.Replace(
        "        npc.getNavigator().setTarget(player, true);",
        "        if (warpCooldown <= 0 && stateTicks > 20) {`r`n            doEvasiveWarp();`r`n            warpCooldown = 60 + random.nextInt(30);`r`n        }`r`n`r`n        npc.getNavigator().setTarget(player, true);"
    )
    Write-Host "Added warp trigger in tick"
} else {
    Write-Host "warp trigger already present"
}

[System.IO.File]::WriteAllText($file, $c, $encoding)

Write-Host ""
Write-Host "=== VERIFICATION ==="
if ($c -match 'decayPlagueEffect') { Write-Host "DECAY STILL PRESENT" } else { Write-Host "Decay removed OK" }
if ($c -match 'warpCooldown') { Write-Host "warpCooldown OK" } else { Write-Host "warpCooldown MISSING" }
if ($c -match 'doEvasiveWarp\(\)') { Write-Host "doEvasiveWarp call OK" } else { Write-Host "doEvasiveWarp call MISSING" }
if ($c -match 'reflectArrow') { Write-Host "reflectArrow OK" } else { Write-Host "reflectArrow MISSING" }
