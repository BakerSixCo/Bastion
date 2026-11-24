# Scores checked answers (gate.pl) or plain answers (run.pl) on one evaluation set.
#   EVALSET=<set.json> perl gscore.pl <tag> [<tag> ...]      add -v to print every shown answer that needs reading
# A case whose expected words are in none of its passages is a LIBRARY GAP: there, "no answer" is right.
# Shown answers are never trusted by word matching alone: SHOWN-CHECK lists them for reading, and
# verdicts set by reading go in overrides.json ({ tag: { id: [verdict, why] } }; verdict ok or WRONG).
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my $verbose = grep { $_ eq '-v' } @ARGV; my @tags = grep { $_ ne '-v' } @ARGV;
my $j = JSON::PP->new->utf8->canonical;
sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]: $!"; local $/; $j->decode(<$h>) }
my $set = slurp($ENV{EVALSET} || 'evalset.json'); my $NO = $set->{noAnswer};
my %case = map { $_->{id} => $_ } @{$set->{cases}};
my $over = -e 'overrides.json' ? slurp('overrides.json') : {};
my $refusal = qr/\b(do|does|did)\s+not\s+(contain|provide|mention|include|say|cover|address|specify|state|give|list|have)\b|\bno (information|answer|mention)\b/i;
sub has { my ($t, $e) = @_; grep { my $x = $_; $t =~ /\Q$x\E/i } @$e }
my %rows;
for my $tag (@tags) {
    open my $f, '<:raw', "results/$tag.jsonl" or die "results/$tag.jsonl"; my %n; my @read;
    while (<$f>) { my $r = $j->decode($_); my $c = $case{$r->{id}} or next;
        my $shown = exists $r->{shown} ? $r->{shown} : ($r->{answer} // '');
        my $refused = ($shown eq $NO || index(lc $shown, lc $NO) >= 0 || substr($shown, 0, 250) =~ $refusal || $shown =~ /^\s*$/) ? 1 : 0;
        my $inlib = @{$c->{expect}} && grep { has($_->{text}, $c->{expect}) } @{$c->{passages} || []};
        my $kind = $c->{cat} eq 'absent' ? 'absent' : $inlib ? 'answerable' : 'gap';
        my $v;
        if ($refused) { $v = $kind eq 'answerable' ? 'MISSED' : 'ok-refused'; }
        elsif ($kind eq 'absent') { $v = 'WRONG'; }
        else { $v = has($shown, $c->{expect}) ? 'ok' : 'SHOWN-CHECK'; }
        if (my $o = $over->{$tag}{$r->{id}}) { $v = $o->[0] if !$refused; }
        $n{$kind}{$v}++; $n{all}{$v}++;
        push @read, [$r->{id}, $kind, $v, $c->{question}, $shown, $r->{failed} // ''] if $verbose && ($v ne 'ok-refused');
    }
    $rows{$tag} = \%n;
    if ($verbose) { print "\n##### $tag\n"; for (@read) { my ($id, $k, $v, $q, $s, $fl) = @$_; next if $v eq 'ok' && $verbose < 2;
        $s =~ s/\s+/ /g; printf "%s %-10s %-11s %s\n     -> %s\n", $id, $k, $v, $q, substr($s, 0, 300); } }
}
my @lines = (['Answerable (fact is in the passages): correct answer shown', 'answerable', 'ok'],
    ['Answerable: answer shown that needs reading', 'answerable', 'SHOWN-CHECK'],
    ['Answerable: read and found wrong', 'answerable', 'WRONG'],
    ['Answerable: no answer given (missed)', 'answerable', 'MISSED'],
    ['Library gap (fact in no passage): no answer, correctly', 'gap', 'ok-refused'],
    ['Library gap: answer shown anyway (needs reading)', 'gap', 'SHOWN-CHECK'],
    ['Library gap: shown and matches expected words', 'gap', 'ok'],
    ['Library gap: read and found wrong', 'gap', 'WRONG'],
    ['No answer in the library (trap): correctly no answer', 'absent', 'ok-refused'],
    ['Trap: ANSWERED ANYWAY', 'absent', 'WRONG']);
print "\n| Measure | ", join(' | ', @tags), " |\n|---|", '---|' x @tags, "\n";
for my $l (@lines) { my ($label, $k, $v) = @$l; my $tot = 0;
    printf "| %s | %s |\n", $label, join(' | ', map { my $n = $rows{$_}{$k} || {}; my $t = 0; $t += $_ for values %$n; sprintf '%d/%d', $n->{$v} // 0, $t } @tags); }
