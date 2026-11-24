sw () 
{ 
    dump;
    local y;
    y=$(tr '>' '\n' < "$SP/ui.xml" | grep -F "text=\"$1" | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | sed 's/[^0-9,]/ /g' | awk '{split($1,a,","); split($2,b,","); print int((a[2]+b[2])/2)}');
    [ -z "$y" ] && { 
        echo "NOT FOUND $1";
        return 1
    };
    "$ADB" shell input tap 880 $y;
    sleep 1
}
