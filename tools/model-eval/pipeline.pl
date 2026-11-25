# The app's checked-answer pipeline, mirrored on the PC (Arc GPU) so one full test takes minutes,
# not an hour on the emulator. Same logic as CheckedAnswer.twoPaths, on search results exported
# from the device (raw wording and restated keyword wording):
#   restated path: answer from the keyword search → code checks → answer judge
#   raw path (only if the restated path passed): answer from the raw search → checks → judge
#   shown = the restated path's answer if both passed, else the fixed no-answer text.
# Inputs come from earlier steps run by pipeline.sh; this script only combines and reports.
#   perl pipeline.pl <tag>       reads results/<tag>-{kw,raw}-checked{,-judge}.jsonl
# Prints a summary and a compact list of shown answers (for reading), and writes results/<tag>.jsonl.
use strict; use warnings; use JSON::PP; binmode STDOUT, ':utf8';
my ($tag) = @ARGV; my $j = JSON::PP->new->utf8->canonical;
sub rows { my %h; open my $f, '<:raw', $_[0] or die "$_[0]: $!"; while (<$f>) { my $r = $j->decode($_); $h{$r->{id}} = $r } \%h }
my ($kc, $kj, $rc, $rj) = map { rows("results/$tag-$_.jsonl") } qw(kw-checked kw-checked-judge raw-checked raw-checked-judge);
my $NO = 'The library does not contain an answer to that.';
my (%n, @shown);
open my $out, '>:raw', "results/$tag.jsonl" or die;
for my $id (sort keys %$kc) {
    my $k = $kc->{$id}; my $trap = $k->{cat} eq 'absent';
    my $stage = $k->{failed} ? "restated $k->{failed}" =~ s/:.*//r
        : !($kj->{$id} && $kj->{$id}{responds}) ? 'restated OFF_TARGET'
        : !$rc->{$id} ? 'raw missing'
        : $rc->{$id}{failed} ? "raw $rc->{$id}{failed}" =~ s/:.*//r
        : !($rj->{$id} && $rj->{$id}{responds}) ? 'raw OFF_TARGET' : 'both';
    my $ok = $stage eq 'both';
    $n{($trap ? 'trap ' : 'answerable ') . ($ok ? 'SHOWN' : 'none')}++; $n{"stage $stage"}++;
    print $out $j->encode({ id => $id, cat => $k->{cat}, stage => $stage, shown => $ok ? $k->{shown} : $NO }), "\n";
    push @shown, [$id, $k->{shown}] if $ok;
}
close $out;
print "$_: $n{$_}\n" for sort keys %n;
print "\nShown answers (first 160 characters):\n";
for (@shown) { (my $a = $_->[1]) =~ s/\s*\[[\d,\s]+\]//g; $a =~ s/\s+/ /g; printf "%s %s\n", $_->[0], substr($a, 0, 160) }
