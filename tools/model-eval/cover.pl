# Subject-coverage check, measured offline on gate.pl results: every subject term of the question
# (BASTION's own keyTerms minus aspect words, same stemmer) must appear in the label or text of
# the source the evidence was quoted from. Terms are tried for the raw question and, with a plan,
# for the restated question; RULE=raw|plan|either picks which must pass.
#   EVALSET=<set> PLANS=<plans.json> RULE=either perl cover.pl <tag> [<tag>...]
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my $j = JSON::PP->new->utf8; sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]"; local $/; $j->decode(<$h>) }
my $set = slurp($ENV{EVALSET}); my %case = map { $_->{id} => $_ } @{$set->{cases}};
my $plans = $ENV{PLANS} ? slurp($ENV{PLANS}) : {}; my $rule = $ENV{RULE} || 'either';
my %stop = map { $_ => 1 } qw(a an and are as at be by can do does for from how i if in into is it its me my of on or should so than that the their them then there these they this to was we what when where which who why will with would you your about tell give please much many long up out off per get got use used using also any some most more very just has have had been being);
my %aspect = map { $_ => 1 } qw(treat treated treating treatment treatments symptom symptoms sign signs cause causes caused first aid manage management cure prevent prevention diagnose diagnosis best way stop help need know rate dose steps deal handle build building built make making construct assemble);
sub stem { my $w = shift; my $cut = sub { length($w) - $_[0] >= 3 ? substr($w, 0, length($w) - $_[0]) : $w };
    return $cut->(3) if $w =~ /ies$/ && length $w > 4; return $cut->(2) if $w =~ /sses$/; return $cut->(2) if $w =~ /(x|ch|sh|ss)es$/;
    return $cut->(3) if $w =~ /ing$/ && length $w > 5; return $cut->(2) if $w =~ /ed$/ && length $w > 4 && $w !~ /eed$/;
    return $cut->(1) if $w =~ /s$/ && $w !~ /(ss|us|is)$/ && length $w > 3; $w }
my %astem = map { stem($_) => 1 } keys %aspect;
sub terms { my %seen; my @t = grep { !$seen{$_}++ } map { stem($_) } grep { length >= 2 && !$stop{$_} } split /[^\p{L}\p{N}]+/, lc shift;
    @t = @t[0..7] if @t > 8; my @s = grep { !$astem{$_} } @t; @s ? @s : @t }
sub covered { my ($q, $text) = @_; my @miss = grep { my $t = $_; $text !~ /\b\Q$t\E/i } terms($q); (!@miss, \@miss) }
for my $tag (@ARGV) { open my $f, '<:raw', "results/$tag.jsonl" or die; my %n;
    while (<$f>) { my $r = $j->decode($_); next if $r->{failed}; my $c = $case{$r->{id}} or next;
        my $form = eval { JSON::PP->new->decode($r->{raw}) } or next;
        my ($src) = grep { $_->{index} == $form->{source} } @{$c->{passages}}; my $text = "$src->{label}\n$src->{text}";
        my ($okr, $mr) = covered($c->{question}, $text);
        my $p = $plans->{$c->{id}}; my ($okp, $mp) = ($p && $p->{valid}) ? covered($p->{standalone_question}, $text) : (0, ['no plan']);
        my $ok = $rule eq 'raw' ? $okr : $rule eq 'plan' ? $okp : ($okr || $okp);
        my $k = $c->{cat} eq 'absent' ? 'trap' : 'answerable'; $n{$k}{$ok ? 'kept' : 'blocked'}++;
        printf "%s %-10s %-7s raw-missing[%s] plan-missing[%s]  %s\n", $r->{id}, $k, $ok ? 'kept' : 'BLOCKED', join(',', @$mr), join(',', @$mp), $c->{question} if $ENV{V} || ($k eq 'trap' ? $ok : !$ok);
    }
    printf "%s rule=%s: shown answers to answerable questions kept %d, blocked %d; traps kept %d, blocked %d\n", $tag, $rule,
        map { $_ // 0 } $n{answerable}{kept}, $n{answerable}{blocked}, $n{trap}{kept}, $n{trap}{blocked};
}
