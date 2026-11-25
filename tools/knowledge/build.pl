# Verifies every knowledge card and writes the approved ones for the app.
#   perl tools/knowledge/build.pl [-v]        (run from the repository root)
# Checks per card (any failure = the card is not built, and the run exits non-zero):
#   - required fields present, id matches the file name, kind is fact or protocol;
#   - every quote's document exists in tools/kit/docs and contains the quote word for word
#     (case, spacing, quote marks and hyphenation at line ends aside);
#   - every number in answer/warnings appears in the card's quotes (no number from nowhere).
# Writes android/app/src/main/assets/knowledge/cards.json with the APPROVED, verified cards only.
use strict; use warnings; use JSON::PP; use File::Basename; binmode STDOUT, ':utf8';
my $verbose = grep { $_ eq '-v' } @ARGV;
my $root = $ENV{KNOWLEDGE_ROOT} // 'tools/knowledge'; my $docs = 'tools/kit/docs';
my $out = $ENV{KNOWLEDGE_OUT} // 'android/app/src/main/assets/knowledge/cards.json';
my $j = JSON::PP->new->utf8->canonical->pretty;

my %text;   # document file -> normalised text
sub norm { my $t = lc shift; $t =~ s/-\s*\n\s*//g; $t =~ s/[\x{2018}\x{2019}\x{201C}\x{201D}"'`\x{00B4}]//g; $t =~ s/[\x{2013}\x{2014}\x{2212}]/-/g;
    $t =~ s/\x{00A0}/ /g; $t =~ s/[^a-z0-9%.,;:()\/-]+/ /g; $t =~ s/\s+/ /g; $t =~ s/^ | $//g; $t }
sub doctext { my $f = shift; return $text{$f} if exists $text{$f}; my $p = "$docs/$f";
    return $text{$f} = undef unless -f $p;
    my $raw = $p =~ /\.pdf$/i ? do { open my $h, '-|', 'pdftotext', '-enc', 'UTF-8', $p, '-' or die "pdftotext: $!"; local $/; my $s = <$h>; utf8::decode($s); $s }
                              : do { open my $h, '<:utf8', $p or die; local $/; <$h> };
    $text{$f} = norm($raw) }
# Numbers as values. Sources often spell them ("boil for one minute"), so words count as numbers too.
my %WORD = (one=>1, two=>2, three=>3, four=>4, five=>5, six=>6, seven=>7, eight=>8, nine=>9, ten=>10, eleven=>11, twelve=>12,
    thirteen=>13, fourteen=>14, fifteen=>15, sixteen=>16, seventeen=>17, eighteen=>18, nineteen=>19, twenty=>20, thirty=>30,
    forty=>40, fifty=>50, sixty=>60, seventy=>70, eighty=>80, ninety=>90, hundred=>100, half=>0.5, once=>1, twice=>2, dozen=>12);
sub nums { my $t = lc shift; $t =~ s/(\d),(\d{3})/$1$2/g; my %n; $n{$1 + 0}++ while $t =~ /(\d+(?:\.\d+)?)/g;
    while ($t =~ /\b(\w+)\b/g) { $n{$WORD{$1}}++ if exists $WORD{$1} } keys %n }

my (@built, @problems); my %seen;
for my $file (sort glob "$root/cards/*.json") {
    my $id = basename($file, '.json'); my @err;
    my $c = eval { open my $h, '<:raw', $file or die; local $/; $j->decode(<$h>) } or do { push @problems, "$id: not valid JSON ($@)"; next };
    for my $k (qw(id kind title asks answer quotes status)) { push @err, "missing $k" unless defined $c->{$k} }
    push @err, "id '$c->{id}' does not match the file name" if ($c->{id} // '') ne $id;
    push @err, "duplicate id" if $seen{$id}++;
    push @err, "kind must be fact or protocol" unless ($c->{kind} // '') =~ /^(fact|protocol)$/;
    push @err, "status must be draft, reviewed or approved" unless ($c->{status} // '') =~ /^(draft|reviewed|approved)$/;
    push @err, "asks must list at least 3 wordings" unless ref $c->{asks} eq 'ARRAY' && @{$c->{asks}} >= 3;
    push @err, "answer must be a non-empty list" unless ref $c->{answer} eq 'ARRAY' && @{$c->{answer}};
    push @err, "at least one quote is required" unless ref $c->{quotes} eq 'ARRAY' && @{$c->{quotes}};
    push @err, "approved without approved_by" if ($c->{status} // '') eq 'approved' && !($c->{approved_by} // '');
    my $quoted = '';
    for my $q (@{ $c->{quotes} || [] }) {
        my $t = doctext($q->{doc} // '');
        if (!defined $t) { push @err, "document not in the kit: " . ($q->{doc} // '(none)'); next }
        my $nq = norm($q->{text} // '');
        if (length($nq) < 20) { push @err, "quote too short: '$q->{text}'"; next }
        if (index($t, $nq) < 0) { push @err, "quote not found word for word in $q->{doc}: '" . substr($q->{text}, 0, 80) . "'"; next }
        $quoted .= ' ' . $q->{text};
    }
    my %have = map { $_ => 1 } nums($quoted);
    my @shown = (@{ $c->{answer} || [] }, @{ $c->{warnings} || [] });
    my @invented = grep { !$have{$_} } map { nums($_) } @shown;
    push @err, "numbers not in the quotes: @invented" if @invented;
    if (@err) { push @problems, map { "$id: $_" } @err; next }
    printf "ok   %-34s %-8s %s\n", $id, $c->{status}, $c->{title} if $verbose;
    push @built, $c if $c->{status} eq 'approved';
}
print "problem: $_\n" for @problems;
my $dir = dirname($out); mkdir $dir unless -d $dir;
my @app = map { my $c = $_; +{ map { $_ => $c->{$_} } grep { exists $c->{$_} } qw(id kind title asks answer warnings quotes) } } @built;
open my $o, '>:raw', $out or die "$out: $!"; print $o $j->encode({ version => 1, cards => \@app }); close $o;
my $all = scalar glob("$root/cards/*.json") ? scalar(() = glob("$root/cards/*.json")) : 0;
printf "%d card file(s), %d problem(s), %d approved card(s) written to %s\n", $all, scalar @problems, scalar @built, $out;
exit(@problems ? 1 : 0);
