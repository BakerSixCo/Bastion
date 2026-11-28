# Tests tools/knowledge/flows.pl against fake fixtures (no real guidance anywhere in here).
#   perl tools/knowledge/flows-test-fixtures/run.pl        (run from the repository root)
# 1. good/: every flow must pass; only the approved one is written.
# 2. Each case below changes a copy of good/fake-good.json in one way; flows.pl must exit non-zero
#    and print the expected problem. The fake document is docs/Fake-Test-Manual.txt (page 2 begins
#    at the form feed), so quotes and page numbers are checked for real.
use strict; use warnings; use JSON::PP; use File::Temp qw(tempdir); use File::Basename;
my $here = dirname(__FILE__); my $j = JSON::PP->new->utf8->canonical->pretty;
my $tmp = tempdir(CLEANUP => 1);
my %env = (KIT_DOCS => "$here/docs", KIT_CACHE => "$tmp/cache", CARDS_DIR => "$here/cards");
sub run { my ($dir) = @_; local @ENV{keys %env} = values %env; local $ENV{FLOWS_DIR} = $dir; local $ENV{FLOWS_OUT} = "$tmp/out.json";
    my $o = `perl tools/knowledge/flows.pl -v 2>&1`; ($? >> 8, $o) }
my $good = do { open my $h, '<:raw', "$here/good/fake-good.json" or die; local $/; $j->decode(<$h>) };
my ($pass, $fail) = (0, 0);
sub ok { my ($c, $name, $detail) = @_; if ($c) { $pass++; print "ok   $name\n" } else { $fail++; print "FAIL $name\n$detail\n" } }

my ($code, $o) = run("$here/good");
ok($code == 0, 'good fixtures pass', $o);
my $built = do { open my $h, '<:raw', "$tmp/out.json" or die; local $/; $j->decode(<$h>) };
ok(@{ $built->{flows} } == 1 && $built->{flows}[0]{id} eq 'fake-good', 'only the approved flow is written', $j->encode($built));
($code, $o) = run("$tmp/none-such");
ok($code == 0 && $o =~ /0 flow file/, 'no flows: {"flows":[]}', $o);

my @cases = (
  ['missing start',     sub { $_[0]{start} = 'nope' },                                   qr/start 'nope' is not a node/],
  ['bad target',        sub { $_[0]{nodes}{'step-c'}{next} = 'ghost' },                  qr/goes to 'ghost'/],
  ['bad answer target', sub { $_[0]{nodes}{lamp}{answers}[0]{next} = 'ghost' },          qr/lamp: goes to 'ghost'/],
  ['unreachable node',  sub { $_[0]{nodes}{orphan} = { type => 'end', text => 'Orphan test end.' } }, qr/orphan: not reachable/],
  ['trap loop',         sub { $_[0]{nodes}{stamp}{next} = 'step-c' },                    qr/no way from here to an end/],
  ['one answer',        sub { splice @{ $_[0]{nodes}{lamp}{answers} }, 1 },              qr/at least 2 answers/],
  ['no quote ref',      sub { $_[0]{nodes}{'step-c'}{quote_refs} = [] },                 qr/step-c: needs at least one quote_ref/],
  ['unknown quote ref', sub { push @{ $_[0]{nodes}{'step-c'}{quote_refs} }, 'zz' },      qr/quote_ref 'zz' is not a quote/],
  ['quote not in doc',  sub { $_[0]{quotes}[0]{text} = 'look at the test lamp and see whether the purple lamp is lit.' }, qr/q-lamp: not found word for word/],
  ['quote wrong page',  sub { $_[0]{quotes}[3]{page} = 1; $_[0]{quotes}[0]{page} = 2 },  qr/q-lamp: not on page 2.*found from page 1/],
  ['doc not in kit',    sub { $_[0]{quotes}[0]{doc} = 'No-Such-Doc.pdf' },               qr/document not in the kit/],
  ['invented number',   sub { $_[0]{nodes}{'step-c'}{text} = 'Test step C: write it 40 times.' }, qr/step-c: numbers not in its quotes: 40/],
  ['invented rate',     sub { $_[0]{nodes}{'step-a'}{tool}{rate_bpm} = 100 },            qr/rate_bpm=100/],
  ['invented timer',    sub { $_[0]{nodes}{wait}{tool}{seconds} = 50 },                  qr/seconds=50/],
  ['cycle as minutes',  sub { $_[0]{nodes}{'step-a'}{tool}{cycle_seconds} = 180 },       qr/cycle_seconds=180/],
  ['number in label',   sub { $_[0]{nodes}{lamp}{answers}[0]{label} = 'Yes, 7 lamps' },  qr/lamp: numbers not in its quotes: 7/],
  ['number not in own refs', sub { $_[0]{nodes}{'step-c'}{text} = 'Test step C: 110 per minute.' }, qr/step-c: numbers not in its quotes: 110/],
  ['bad tool kind',     sub { $_[0]{nodes}{stamp}{tool}{kind} = 'laser' },               qr/tool.kind must be/],
  ['timer no seconds',  sub { delete $_[0]{nodes}{wait}{tool}{seconds} },                qr/timer needs seconds/],
  ['question with next',sub { $_[0]{nodes}{lamp}{next} = 'end' },                        qr/not next/],
  ['action without next', sub { delete $_[0]{nodes}{'step-c'}{next} },                   qr/step-c: needs next/],
  ['bad type',          sub { $_[0]{nodes}{'step-c'}{type} = 'maybe' },                  qr/type must be/],
  ['approved, no approver', sub { $_[0]{approved_by} = '' },                             qr/approved without approved_by/],
  ['bad status',        sub { $_[0]{status} = 'final' },                                 qr/status must be/],
  ['id not file name',  sub { $_[0]{id} = 'other' },                                     qr/does not match the file name/],
  ['link not a card',   sub { $_[0]{links} = ['no-such-card'] },                         qr/link 'no-such-card' is not a card/],
);
for my $c (@cases) {
    my ($name, $mut, $want) = @$c;
    my $f = $j->decode($j->encode($good)); $mut->($f);
    my $d = "$tmp/case" . ++(my $n = $pass + $fail); mkdir $d; mkdir "$d";
    open my $h, '>:raw', "$d/fake-good.json" or die; print $h $j->encode($f); close $h;
    my ($code, $o) = run($d);
    ok($code != 0 && $o =~ $want, "rejects: $name", "exit $code, output:\n$o");
}
print "$pass passed, $fail failed\n"; exit($fail ? 1 : 0);
