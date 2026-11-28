# Verifies every guided flow and writes the approved ones for the app.
#   perl tools/knowledge/flows.pl [-v]        (run from the repository root)
# Format: tools/knowledge/flows/README.md. Checks per flow (any failure = the flow is not built,
# and the run exits non-zero):
#   - required fields, id matches the file name, status draft|reviewed|approved, approved_by set
#     when approved, node types question|action|tool|end;
#   - structure: start exists; every next/answer target exists; every node reachable from start;
#     an end reachable from every node (no trap loops); questions have >= 2 answers; actions and
#     tools have a next; ends have neither; tool kinds and their parameters;
#   - every question/action/tool node has >= 1 quote_ref and each ref names a quote of the flow;
#   - every quote's document is in the kit and contains the quote word for word (same rule as
#     build.pl), on the stated page or the next one (a quote may run over a page break);
#   - every number in a node's text, warnings, answer labels, log text or tool parameters appears
#     in one of that node's referenced quotes (a duration in seconds may appear as minutes).
# Writes android/app/src/main/assets/knowledge/flows.json with the APPROVED, verified flows only
# ({"flows":[]} when none). Text extraction is shared with find.pl: pdftotext per page, cached in
# tools/kit/.textcache/<file>.txt. Normalisation and number matching are copied from build.pl.
# Environment overrides (for the test fixtures): FLOWS_DIR, KIT_DOCS, KIT_CACHE, CARDS_DIR, FLOWS_OUT.
use strict; use warnings; use JSON::PP; use File::Basename; binmode STDOUT, ':utf8';
my $verbose = grep { $_ eq '-v' } @ARGV;
my $dir = $ENV{FLOWS_DIR} // 'tools/knowledge/flows';
my $docs = $ENV{KIT_DOCS} // 'tools/kit/docs';
my $cache = $ENV{KIT_CACHE} // 'tools/kit/.textcache';
my $cards = $ENV{CARDS_DIR} // 'tools/knowledge/cards';
my $out = $ENV{FLOWS_OUT} // 'android/app/src/main/assets/knowledge/flows.json';
my $j = JSON::PP->new->utf8->canonical->pretty;

# --- same text rules as build.pl ---
sub norm { my $t = lc shift; $t =~ s/-\s*\n\s*//g; $t =~ s/[\x{2018}\x{2019}\x{201C}\x{201D}"'`\x{00B4}]//g; $t =~ s/[\x{2013}\x{2014}\x{2212}]/-/g;
    $t =~ s/\x{00A0}/ /g; $t =~ s/[^a-z0-9%.,;:()\/-]+/ /g; $t =~ s/\s+/ /g; $t =~ s/^ | $//g; $t }
my %WORD = (one=>1, two=>2, three=>3, four=>4, five=>5, six=>6, seven=>7, eight=>8, nine=>9, ten=>10, eleven=>11, twelve=>12,
    thirteen=>13, fourteen=>14, fifteen=>15, sixteen=>16, seventeen=>17, eighteen=>18, nineteen=>19, twenty=>20, thirty=>30,
    forty=>40, fifty=>50, sixty=>60, seventy=>70, eighty=>80, ninety=>90, hundred=>100, half=>0.5, once=>1, twice=>2, dozen=>12);
sub nums { my $t = lc shift; $t =~ s/(\d),(\d{3})/$1$2/g; my %n; $n{$1 + 0}++ while $t =~ /(\d+(?:\.\d+)?)/g;
    while ($t =~ /\b(\w+)\b/g) { $n{$WORD{$1}}++ if exists $WORD{$1} } keys %n }

# --- document pages (find.pl's cache: pdftotext output, pages split by form feeds) ---
my %pages;   # document file -> [normalised page texts], or undef when not in the kit
sub docpages { my $f = shift; return $pages{$f} if exists $pages{$f};
    my $p = "$docs/$f"; return $pages{$f} = undef unless $f ne '' && -f $p;
    my $raw;
    if ($p =~ /\.pdf$/i) {
        mkdir $cache unless -d $cache; my $c = "$cache/$f.txt";
        if (!-f $c || -M $c > -M $p) { system('pdftotext', '-q', '-enc', 'UTF-8', $p, $c) == 0 or die "pdftotext failed on $p\n" }
        open my $h, '<:utf8', $c or die "$c: $!"; local $/; $raw = <$h>;
    } else { open my $h, '<:utf8', $p or die "$p: $!"; local $/; $raw = <$h> }
    $pages{$f} = [ map { norm($_) } split /\f/, $raw, -1 ] }

my %TYPES = map { $_ => 1 } qw(question action tool end);
my %KINDS = map { $_ => 1 } qw(metronome timer timestamp);

sub check_flow { my ($f, $id) = @_; my @err;
    for my $k (qw(id title status start nodes quotes)) { push @err, "missing $k" unless defined $f->{$k} }
    return @err if @err;
    push @err, "id '$f->{id}' does not match the file name" if $f->{id} ne $id;
    push @err, "status must be draft, reviewed or approved" unless $f->{status} =~ /^(draft|reviewed|approved)$/;
    push @err, "approved without approved_by" if $f->{status} eq 'approved' && !($f->{approved_by} // '');
    push @err, "entry must be true or false" if exists $f->{entry} && !JSON::PP::is_bool($f->{entry});
    my $nodes = $f->{nodes}; return (@err, "nodes must be an object with at least one node") unless ref $nodes eq 'HASH' && %$nodes;
    push @err, "quotes must be a list" unless ref $f->{quotes} eq 'ARRAY';
    push @err, "links must be a list" if exists $f->{links} && ref $f->{links} ne 'ARRAY';
    # Quotes: unique ids, verbatim in the kit, on the stated page.
    my %quote;
    for my $q (ref $f->{quotes} eq 'ARRAY' ? @{ $f->{quotes} } : ()) {
        my $qid = $q->{id} // ''; if ($qid eq '') { push @err, "a quote has no id"; next }
        push @err, "duplicate quote id '$qid'" if $quote{$qid};
        my $pg = docpages($q->{doc} // '');
        if (!defined $pg) { push @err, "quote $qid: document not in the kit: " . ($q->{doc} // '(none)'); next }
        my $nq = norm($q->{text} // '');
        if (length($nq) < 20) { push @err, "quote $qid: too short"; next }
        if (index(join(' ', @$pg), $nq) < 0) { push @err, "quote $qid: not found word for word in $q->{doc}: '" . substr($q->{text}, 0, 80) . "'"; next }
        if (defined $q->{page} && $q->{page} ne '') {
            my $n = $q->{page};
            if ($n !~ /^\d+$/ || $n < 1 || $n > @$pg) { push @err, "quote $qid: page '$n' is not a page of $q->{doc}"; next }
            my $span = $pg->[$n - 1] . ($n < @$pg ? ' ' . $pg->[$n] : '');
            # Starts on page n: in page n (plus the start of n+1), not wholly on page n+1.
            if (index($span, $nq) < 0 || ($n < @$pg && index($pg->[$n - 1], $nq) < 0 && index($pg->[$n], $nq) >= 0)) {
                my ($at) = grep { index($pg->[$_ - 1], $nq) >= 0 } 1 .. @$pg;
                ($at) = grep { index($pg->[$_ - 1] . ($_ < @$pg ? ' ' . $pg->[$_] : ''), $nq) >= 0 } 1 .. @$pg unless $at;
                push @err, "quote $qid: not on page $n of $q->{doc}" . ($at ? " (found from page $at)" : ''); next }
        }
        $quote{$qid} = $q->{text};
    }
    # Nodes.
    my %edges;
    for my $nid (sort keys %$nodes) { my $n = $nodes->{$nid}; my $t = $n->{type} // '';
        if (!$TYPES{$t}) { push @err, "node $nid: type must be question, action, tool or end"; next }
        push @err, "node $nid: no text" unless ($n->{text} // '') =~ /\S/;
        my @to;
        if ($t eq 'question') {
            my $a = ref $n->{answers} eq 'ARRAY' ? $n->{answers} : [];
            push @err, "node $nid: a question needs at least 2 answers" if @$a < 2;
            for my $ans (@$a) { push @err, "node $nid: an answer has no label" unless ($ans->{label} // '') =~ /\S/; push @to, $ans->{next} // '' }
            push @err, "node $nid: a question goes on by its answers, not next" if defined $n->{next};
        } elsif ($t eq 'end') {
            push @err, "node $nid: an end has no next or answers" if defined $n->{next} || defined $n->{answers};
        } else {
            push @to, $n->{next} // ''; push @err, "node $nid: needs next" unless defined $n->{next};
            push @err, "node $nid: only a question has answers" if defined $n->{answers};
        }
        if ($t eq 'tool') { my $tl = $n->{tool};
            if (ref $tl ne 'HASH' || !$KINDS{$tl->{kind} // ''}) { push @err, "node $nid: tool.kind must be metronome, timer or timestamp" }
            elsif ($tl->{kind} eq 'metronome') {
                push @err, "node $nid: metronome needs rate_bpm between 1 and 300" unless ($tl->{rate_bpm} // 0) =~ /^\d+(\.\d+)?$/ && $tl->{rate_bpm} > 0 && $tl->{rate_bpm} <= 300;
                for my $k (qw(compressions breaths cycle_seconds)) { push @err, "node $nid: $k must be a whole number >= 0" if defined $tl->{$k} && $tl->{$k} !~ /^\d+$/ }
                push @err, "node $nid: breaths without compressions" if ($tl->{breaths} // 0) > 0 && !($tl->{compressions} // 0);
            } elsif ($tl->{kind} eq 'timer') {
                push @err, "node $nid: timer needs seconds > 0" unless ($tl->{seconds} // '') =~ /^\d+$/ && $tl->{seconds} > 0;
            } else { push @err, "node $nid: timestamp needs a label" unless ($tl->{label} // '') =~ /\S/ }
        } elsif (defined $n->{tool}) { push @err, "node $nid: only a tool node has tool" }
        for my $to (@to) { if (!exists $nodes->{$to}) { push @err, "node $nid: goes to '$to', which is not a node" } else { push @{ $edges{$nid} }, $to } }
        # Quote refs and numbers.
        my @refs = ref $n->{quote_refs} eq 'ARRAY' ? @{ $n->{quote_refs} } : ();
        push @err, "node $nid: needs at least one quote_ref" if $t ne 'end' && !@refs;
        my @bad = grep { my $r = $_; !grep { ($_->{id} // '') eq $r } @{ ref $f->{quotes} eq 'ARRAY' ? $f->{quotes} : [] } } @refs;
        push @err, "node $nid: quote_ref '$_' is not a quote of this flow" for @bad;
        my %have = map { $_ => 1 } nums(join ' ', map { $quote{$_} // '' } @refs);
        my @shown = ($n->{text} // '', @{ ref $n->{warnings} eq 'ARRAY' ? $n->{warnings} : [] }, $n->{log} // '',
            map { $_->{label} // '' } @{ ref $n->{answers} eq 'ARRAY' ? $n->{answers} : [] });
        my $tl = ref $n->{tool} eq 'HASH' ? $n->{tool} : {};
        push @shown, $tl->{label} // '';
        my @invented = grep { !$have{$_} } map { nums($_) } @shown;
        for my $k (qw(rate_bpm compressions breaths)) { push @invented, "$k=$tl->{$k}" if ($tl->{$k} // 0) && !$have{$tl->{$k} + 0} }
        for my $k (qw(cycle_seconds seconds)) { my $s = $tl->{$k} // 0; next unless $s;
            push @invented, "$k=$s" unless $have{$s + 0} || ($s % 60 == 0 && $have{$s / 60}) || ($s % 3600 == 0 && $have{$s / 3600}) }
        push @err, "node $nid: numbers not in its quotes: @invented" if @invented;
    }
    return @err if @err;
    # Reachable from start; an end reachable from everywhere.
    return (@err, "start '$f->{start}' is not a node") unless exists $nodes->{$f->{start}};
    my %seen = ($f->{start} => 1); my @todo = ($f->{start});
    while (@todo) { my $x = shift @todo; for my $y (@{ $edges{$x} || [] }) { push @todo, $y unless $seen{$y}++ } }
    push @err, "node $_: not reachable from start" for grep { !$seen{$_} } sort keys %$nodes;
    my %back; for my $x (keys %edges) { push @{ $back{$_} }, $x for @{ $edges{$x} } }
    my %ok = map { $_ => 1 } grep { $nodes->{$_}{type} eq 'end' } keys %$nodes; @todo = keys %ok;
    push @err, "no end node" unless @todo;
    while (@todo) { my $x = shift @todo; for my $y (@{ $back{$x} || [] }) { push @todo, $y unless $ok{$y}++ } }
    push @err, "node $_: no way from here to an end (trap loop)" for grep { !$ok{$_} && $seen{$_} } sort keys %$nodes;
    for my $l (@{ $f->{links} || [] }) { push @err, "link '$l' is not a card" unless -f "$cards/$l.json" }
    @err }

my (@built, @problems, %seen);
my @files = sort glob "$dir/*.json";
for my $file (@files) {
    my $id = basename($file, '.json');
    my $f = eval { open my $h, '<:raw', $file or die; local $/; $j->decode(<$h>) } or do { push @problems, "$id: not valid JSON ($@)"; next };
    my @err = ref $f eq 'HASH' ? check_flow($f, $id) : ('not a JSON object');
    push @err, "duplicate id" if $seen{$id}++;
    if (@err) { push @problems, map { "$id: $_" } @err; printf "FAIL %-30s %s\n", $id, scalar(@err) . ' problem(s)' if $verbose; next }
    printf "ok   %-30s %-8s %d node(s)  %s\n", $id, $f->{status}, scalar(keys %{ $f->{nodes} }), $f->{title} if $verbose;
    push @built, $f if $f->{status} eq 'approved';
}
print "problem: $_\n" for @problems;
my $od = dirname($out); mkdir $od unless -d $od;
my @app = map { my $f = $_; +{ map { $_ => $f->{$_} } grep { exists $f->{$_} } qw(id title entry status approved_by start nodes quotes links) } } @built;
open my $o, '>:raw', $out or die "$out: $!"; print $o $j->encode({ version => 1, flows => \@app }); close $o;
printf "%d flow file(s), %d problem(s), %d approved flow(s) written to %s\n", scalar @files, scalar @problems, scalar @built, $out;
exit(@problems ? 1 : 0);
