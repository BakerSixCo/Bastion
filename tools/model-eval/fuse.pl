# Question-restating test, step 2: what BASTION's own search returns for each wording of a
# question (evalvariants-w0.json: the app's normal five passages; evalvariants-w10.json: its ten
# best candidates), compared with the raw question alone.
#   perl fuse.pl <tag>
# Arms: original (raw question), keyword / standalone / alternate / semantic (one restated
# wording instead of the raw question), rrf (all wordings fused by reciprocal rank fusion).
# "Hit" is a crude stand-in for recall until gold passages exist: at least one of the five
# passages contains one of the words expected in the answer.
# Writes evalset-<tag>-<arm>.json for each arm, so the answer models can be run on them.
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my ($tag) = @ARGV; my $j = JSON::PP->new->utf8->canonical;
sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]: $!"; local $/; $j->decode(<$h>) }
my $set = slurp('evalset.json'); my $ret = slurp('evalset-retrieved.json');
my %expect = map { $_->{id} => $_->{expect} } @{$ret->{cases}};          # as first written: the library should answer r01-r26
my %case = map { $_->{id} => $_ } @{$set->{cases}};
my (%w0, %w10);
$w0{$_->{id}}{$_->{variant}} = $_ for @{ slurp("evalvariants-w0.json")->{cases} };
$w10{$_->{id}}{$_->{variant}} = $_ for @{ slurp("evalvariants-w10.json")->{cases} };
sub key { my $p = shift; $p->{label} . '|' . substr($p->{text}, 0, 80) }
sub hit { my ($id, $ps) = @_; my @e = @{ $expect{$id} || [] }; return undef unless @e; (grep { my $t = lc $_->{text}; grep { index($t, lc $_) >= 0 } @e } @$ps) ? 1 : 0 }
sub pick { my @top = @_; my (@keep, %per); my $chars = 0;      # the app's own limits: five passages, three per document, 3000 characters
    for my $x (@top) { last if @keep >= 5; my $doc = (split / \x{2014} /, $x->{label})[0];
        next if ($per{$doc} // 0) >= 3; next if @keep && $chars + length($x->{text}) > 3000; $per{$doc}++; $chars += length $x->{text}; push @keep, $x; }
    my $n = 0; [map { $n++; { index => $n, label => $_->{label}, text => $_->{text} } } @keep] }
my @arms = qw(original keyword standalone alternate semantic rrf); my (%res, %out);
for my $id (sort keys %w0) {
    for my $arm (@arms) {
        my $ps;
        if ($arm eq 'rrf') { my (%score, %obj);
            for my $v (sort keys %{ $w10{$id} || {} }) { my $rank = 0; for my $p (@{ $w10{$id}{$v}{passages} }) { $rank++; my $k = key($p); $score{$k} += 1 / (60 + $rank); $obj{$k} //= $p; } }
            $ps = pick(map { $obj{$_} } sort { $score{$b} <=> $score{$a} || $a cmp $b } keys %score);
        } else { my $e = $w0{$id}{$arm} or next; $ps = [map { { index => $_->{index}, label => $_->{label}, text => $_->{text} } } @{ $e->{passages} }]; }
        $res{$arm}{$id} = { hit => hit($id, $ps), n => scalar @$ps, labels => [map { substr($_->{label}, 0, 46) } @$ps] };
        if (my $c = $case{$id}) { push @{ $out{$arm} }, { %$c, passages => $ps }; }
    }
}
print "| Search wording | Expected fact in the five passages (26 questions the library should answer) | No passages at all |\n|---|---|---|\n";
for my $arm (@arms) { my @ids = grep { $_ le 'r26' && defined $res{$arm}{$_} } sort keys %{ $res{$arm} };
    printf "| %s | %d/%d | %d |\n", $arm, scalar(grep { $res{$arm}{$_}{hit} } @ids), scalar @ids, scalar(grep { !$res{$arm}{$_}{n} } keys %{ $res{$arm} }); }
print "\nPer question (1 = expected fact present, 0 = absent, - = no expected words):\n";
for my $id (sort keys %w0) { printf "%s %-48s %s\n", $id, substr($case{$id}{question}, 0, 48), join(' ', map { my $r = $res{$_}{$id}; sprintf '%s=%s', substr($_, 0, 4), !$r ? '?' : defined $r->{hit} ? $r->{hit} : '-' } @arms); }
for my $arm (@arms) { next unless $out{$arm}; open my $h, '>:raw', "evalset-$tag-$arm.json" or die;
    print $h JSON::PP->new->utf8->canonical->pretty->encode({ system => $set->{system}, noAnswer => $set->{noAnswer}, cases => $out{$arm} }); close $h; }
if (@ARGV > 1) { shift @ARGV; for my $id (@ARGV) { print "\n##### $id $case{$id}{question}\n"; for my $arm (@arms) { my $r = $res{$arm}{$id} or next; print "  $arm: ", join(' ; ', @{ $r->{labels} }), "\n"; } } }
