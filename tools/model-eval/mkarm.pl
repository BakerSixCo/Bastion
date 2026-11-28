# Builds an evaluation set whose passages come from one restated wording's search.
#   perl mkarm.pl <set.json> <variants.json> <arm> <out.json>
# arm: original, keyword, standalone, alternate, semantic. A case with no result for that
# wording keeps the passages from <set.json> (the raw question's search).
use strict; use warnings; use JSON::PP;
my ($setf, $varf, $arm, $outf) = @ARGV; my $j = JSON::PP->new->utf8->canonical->pretty;
sub slurp { open my $h, '<:raw', $_[0] or die "$_[0]: $!"; local $/; $j->decode(<$h>) }
my $set = slurp($setf); my %v;
$v{$_->{id}}{$_->{variant}} = $_ for @{ slurp($varf)->{cases} };
my $swapped = 0;
for my $c (@{$set->{cases}}) { my $e = $v{$c->{id}}{$arm} or next; $c->{passages} = $e->{passages}; $c->{searched_as} = $e->{question}; $swapped++; }
open my $h, '>:raw', $outf or die; print $h $j->encode($set); close $h;
print "$outf: $swapped of ", scalar @{$set->{cases}}, " cases searched with the $arm wording\n";
