# Moves cards to and from the owner's review page (https://claude.ai/artifact/H8nmtGzCwuGvoh54FEj2dz).
#   perl tools/knowledge/review/sync.pl export [status...]   # default: reviewed
#       writes tools/knowledge/review/out/<id>.json, one page document per card, for ArtifactData
#       batch "set" into collection "cards". Each carries `version`: a fingerprint of exactly what
#       the owner is approving (kind, title, answer, warnings, quotes).
#   perl tools/knowledge/review/sync.pl import <dir>          # dir = ArtifactData list out_dir
#       reads <dir>/decisions/<id>.json and applies them to tools/knowledge/cards/<id>.json:
#       approve → status "approved", approved_by "owner (review page)"; fix/reject → status
#       "draft" with owner_decision and owner_note. A decision whose fingerprint no longer matches
#       the card is ignored (the card changed after the owner saw it).
use strict; use warnings; use JSON::PP; use Digest::SHA qw(sha256_hex); use File::Basename; binmode STDOUT, ':utf8';
my $cards = 'tools/knowledge/cards'; my $out = 'tools/knowledge/review/out';
my $j = JSON::PP->new->utf8->canonical; my $pretty = JSON::PP->new->utf8->canonical->pretty;
sub load { open my $h, '<:raw', $_[0] or die "$_[0]: $!"; local $/; $j->decode(<$h>) }
sub fingerprint { my $c = shift; substr(sha256_hex($j->encode({ map { $_ => $c->{$_} } qw(kind title answer warnings quotes) })), 0, 16) }
my $cmd = shift // ''; my $order = 0;
if ($cmd eq 'export') {
    my %want = map { $_ => 1 } (@ARGV ? @ARGV : ('reviewed')); mkdir $out unless -d $out; my $n = 0;
    for my $f (sort glob "$cards/*.json") {
        my $c = load($f); $order++; next unless $want{$c->{status} // ''};
        my %doc = (map { $_ => $c->{$_} } grep { exists $c->{$_} } qw(kind title answer warnings quotes asks conflict review batch));
        $doc{version} = fingerprint($c); $doc{order} = $order;
        open my $o, '>:raw', "$out/$c->{id}.json" or die; print $o $j->encode(\%doc); close $o; $n++;
        printf "%-36s %s\n", $c->{id}, $doc{version};
    }
    print "$n card(s) written to $out/\n";
}
elsif ($cmd eq 'import') {
    my $dir = shift or die "import needs the out_dir of an ArtifactData list of 'decisions'\n";
    my ($ok, $stale, $missing) = (0, 0, 0);
    for my $f (sort glob "$dir/decisions/*.json") {
        my $id = basename($f, '.json'); my $d = load($f); $d = $d->{data} if ref $d->{data} eq 'HASH';
        my $cf = "$cards/$id.json"; unless (-f $cf) { print "no card file for decision $id\n"; $missing++; next }
        my $c = load($cf);
        if (($d->{version} // '') ne fingerprint($c)) { print "STALE  $id: the card changed after this decision; ignored\n"; $stale++; next }
        if ($d->{decision} eq 'approve') { $c->{status} = 'approved'; $c->{approved_by} = 'owner (review page)'; delete $c->{owner_note} }
        else { $c->{status} = 'draft'; $c->{approved_by} = ''; $c->{owner_decision} = $d->{decision}; $c->{owner_note} = $d->{note} // '' }
        $c->{owner_decided_at} = $d->{at} if $d->{at};
        open my $o, '>:raw', $cf or die; print $o $pretty->encode($c); close $o; $ok++;
        printf "%-8s %s%s\n", $d->{decision}, $id, ($d->{note} ? "  — $d->{note}" : '');
    }
    print "$ok applied, $stale stale, $missing without a card\n";
}
else { die "usage: sync.pl export [status...] | import <dir>\n" }
