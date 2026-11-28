# Prints the answers each model gave for the listed case ids.   perl show.pl <id> [<id> ...]
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my %w = map { $_ => 1 } @ARGV; my $j = JSON::PP->new->utf8;
open my $h, '<:raw', 'evalset.json' or die; my $set = do { local $/; $j->decode(<$h>) };
my %q = map { $_->{id} => $_->{question} } @{$set->{cases}};
my %a; for my $f (sort glob 'results/*.jsonl') { my ($t) = $f =~ m{/(.+)\.jsonl}; open my $r, '<:raw', $f or next;
    while (<$r>) { my $x = $j->decode($_); next unless $w{$x->{id}}; my $s = $x->{answer} // '';
        if ($s =~ /<\|answer_start\|>/) { my ($st) = $s =~ /<\|status_start\|>\s*(\w+)/; my ($an) = $s =~ /<\|answer_start\|>(.*?)(?:<\|answer_end\|>|$)/s; $s = "[$st] $an"; }
        $s =~ s/\s+/ /g; $a{$x->{id}}{$t} = substr($s, 0, 520); } }
for my $id (@ARGV) { print "\n### $id $q{$id}\n"; print "  $_: $a{$id}{$_}\n" for sort keys %{$a{$id}}; }
