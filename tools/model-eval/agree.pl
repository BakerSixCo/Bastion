# Agreement check, offline: is the passage an answer cites also among the passages found when the
# question is searched in restated words?  perl agree.pl <checked-tag> <raw set> <other set> [<other set>...]
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my ($tag, $rawf, @others) = @ARGV; my $j = JSON::PP->new->utf8;
sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]"; local $/; $j->decode(<$h>) }
my %raw = map { $_->{id} => $_ } @{ slurp($rawf)->{cases} };
my @o = map { my $s = slurp($_); +{ map { $_->{id} => $_ } @{$s->{cases}} } } @others;
sub key { my $p = shift; lc(substr($p->{label}, 0, 60)) . '|' . lc(substr($p->{text}, 0, 60)) }
sub doc { my $l = lc shift->{label}; $l =~ s/ \x{2014} .*//; $l }
open my $f, '<:raw', "results/$tag.jsonl" or die;
while (<$f>) { my $r = $j->decode($_); next if $r->{failed}; my $c = $raw{$r->{id}};
    my %cited; $cited{$_}++ for map { split /\s*,\s*/ } $r->{shown} =~ /\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\]/g;
    my @src = grep { $cited{$_->{index}} } @{$c->{passages}}; @src = @{$c->{passages}} unless @src;
    my (%okeys, %odocs); for my $o (@o) { for my $p (@{ ($o->{$r->{id}} || {})->{passages} || [] }) { $okeys{key($p)}++; $odocs{doc($p)}++ } }
    my $pass = grep { $okeys{key($_)} } @src; my $dpass = grep { $odocs{doc($_)} } @src;
    printf "%s %-10s passage=%s doc=%s | %s\n", $r->{id}, $r->{cat}, $pass ? 'yes' : 'NO ', $dpass ? 'yes' : 'NO ', substr($c->{question}, 0, 60);
}
