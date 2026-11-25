# Code checks on ordinary free-text answers (run.pl output), no model judgement involved:
#   1. it is not a refusal and cites at least one existing source [n]
#   2. every subject term of the question appears in the cited sources (label or text)  (cover.pl's rule)
#   3. every number in the answer appears in the passages
# Anything failing shows the fixed no-answer text.  EVALSET=<set> perl plaincheck.pl <tag> [-v]  -> results/<tag>-checked.jsonl
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my ($tag, $v) = @ARGV; my $j = JSON::PP->new->utf8->canonical;
sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]"; local $/; $j->decode(<$h>) }
my $set = slurp($ENV{EVALSET}); my %case = map { $_->{id} => $_ } @{$set->{cases}}; my $NO = $set->{noAnswer};
do './cover-lib.pl' or die $@ || $!;
my $plans = $ENV{PLANS} ? slurp($ENV{PLANS}) : {};
my $refusal = qr/\b(do|does|did)\s+not\s+(contain|provide|mention|include|say|cover|address|specify|state|give|list|have)\b|\bno (information|answer|mention)\b/i;
sub nums { my $t = shift; $t =~ s/(\d),(\d{3})/$1$2/g; my %n; $n{$1 + 0}++ while $t =~ /(\d+(?:\.\d+)?)/g; keys %n }
open my $in, '<:raw', "results/$tag.jsonl" or die; open my $out, '>:raw', "results/$tag-checked.jsonl" or die; my %n;
while (<$in>) { my $r = $j->decode($_); my $c = $case{$r->{id}} or next; my $a = $r->{answer} // ''; my $ps = $c->{passages} || [];
    my $fail = '';
    if ($a =~ /^\s*$/ || index(lc $a, lc $NO) >= 0 || substr($a, 0, 250) =~ $refusal) { $fail = 'refusal' }
    else { my %cited; $cited{$_}++ for map { split /\s*,\s*/ } $a =~ /\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\]/g;
        my @src = grep { $cited{$_->{index}} } @$ps;
        @src = @$ps if !@src;   # uncited answer: the subject must still be in the passages it was given
        if (0) { }
        else { my $text = join "\n", map { "$_->{label}\n$_->{text}" } @src; my @miss = grep { !present($_, $text) } terms($c->{question});
            # Slang: the restated question's subject words may stand in (as CheckedAnswer.check does).
            if (@miss && (my $p = $plans->{$r->{id}})) { @miss = () if $p->{valid} && !grep { !present($_, $text) } terms($p->{standalone_question}) }
            if (@miss) { $fail = 'cover:' . join(',', @miss) }
            else { my %have = map { $_ => 1 } map { nums($_->{text}) } @$ps; my @m = grep { !$have{$_} } nums($a =~ s/\[[\d,\s]+\]//gr); $fail = 'number:' . join(',', @m) if @m; } } }
    my %o = (id => $r->{id}, cat => $r->{cat}, shown => $fail ? $NO : $a, failed => $fail, answer => $a);
    print $out $j->encode(\%o), "\n"; my $f = $fail; $f =~ s/:.*//; $n{$f || 'SHOWN'}++;
    printf "%s %-10s %-28s %s\n", $r->{id}, $c->{cat}, $fail || 'SHOWN', $c->{question} if $v && $fail && $fail ne 'refusal';
}
print join(', ', map { "$_=$n{$_}" } sort keys %n), "\n";
