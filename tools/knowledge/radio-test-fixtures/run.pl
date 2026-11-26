# Run from the repository root: perl tools/knowledge/radio-test-fixtures/run.pl
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Temp qw(tempdir);
use JSON::PP;
use Test::More;

my $here = dirname(__FILE__);
my $scratch = tempdir('run-XXXXXX', DIR => $here, CLEANUP => 1);
my $json = JSON::PP->new->utf8;

sub fixture {
    my ($id) = @_;
    return {
        id => $id,
        status => 'approved',
        approved_by => 'Fake approver',
        kind => 'protocol',
        title => 'Imaginary cardboard moon',
        asks => ['stamp the pretend moon', 'label the cardboard moon', 'where is the fake stamp'],
        answer => ['Pretend to stamp the cardboard moon [1].', 'Draw a  purple pretend flag.'],
        warnings => ['Keep the imaginary moon away from the fake buzzer.'],
        quotes => [{
            doc => 'Fake-Radio-Manual.txt',
            text => 'In this imaginary game, label the cardboard moon with the pretend stamp marked one.',
        }],
        radio => ['a2', 'w1', 'a1'],
    };
}

sub run_build {
    my (@cards) = @_;
    local $ENV{KNOWLEDGE_ROOT} = tempdir('case-XXXXXX', DIR => $scratch);
    local $ENV{KIT_DOCS} = "$here/docs";
    local $ENV{KNOWLEDGE_OUT} = "$ENV{KNOWLEDGE_ROOT}/out.json";
    mkdir "$ENV{KNOWLEDGE_ROOT}/cards" or die "mkdir cards: $!";
    for my $card (@cards) {
        my $file = "$ENV{KNOWLEDGE_ROOT}/cards/$card->{id}.json";
        open my $out, '>:raw', $file or die "$file: $!";
        print {$out} $json->encode($card);
        close $out or die "$file: $!";
    }
    open my $build, '-|', $^X, 'tools/knowledge/build.pl' or die "start builder: $!";
    local $/;
    my $log = <$build>;
    close $build;
    my $status = $?;
    open my $out, '<:raw', $ENV{KNOWLEDGE_OUT} or die "$ENV{KNOWLEDGE_OUT}: $!\n$log";
    return ($status >> 8, $log, $json->decode(<$out>)->{cards});
}

my $radio = fixture('fake-radio');
my $legacy = fixture('fake-legacy');
delete $legacy->{radio};
my $draft = fixture('fake-draft');
$draft->{status} = 'draft';
my ($exit, $log, $cards) = run_build($radio, $legacy, $draft);
is($exit, 0, 'valid references pass') or diag($log);
is_deeply([map { $_->{id} } @$cards], ['fake-legacy', 'fake-radio'], 'only approved cards are bundled');
ok(!exists $cards->[0]{radio}, 'the radio field remains optional');
is_deeply($cards->[1]{radio}, [$radio->{answer}[1], $radio->{warnings}[0], $radio->{answer}[0]],
    'approved strings are resolved verbatim in sending order');

sub rejects {
    my ($card, $message, $name) = @_;
    my ($status, $output, $built) = run_build($card);
    is($status, 1, "$name exits nonzero") or diag($output);
    like($output, $message, $name);
    is_deeply($built, [], "$name excludes the card");
}

for my $ref ('a3', 'w2') {
    my $card = fixture('fake-invalid');
    $card->{radio} = [$ref];
    rejects($card, qr/radio reference '\Q$ref\E' does not point to an existing line/, "invalid reference $ref");
}
my $malformed = fixture('fake-malformed');
$malformed->{radio} = ['a01'];
rejects($malformed, qr/radio reference must have the form/, 'noncanonical reference');
my $empty = fixture('fake-empty');
$empty->{radio} = [];
rejects($empty, qr/radio must be a non-empty list/, 'empty radio');
my $blank = fixture('fake-blank');
$blank->{answer} = [" \t\r"];
$blank->{radio} = ['a1'];
rejects($blank, qr/radio reference 'a1' must point to a non-blank text line/, 'blank referenced line');

my $boundary = fixture('fake-boundary');
$boundary->{title} = "\x{e9}" x 93;
$boundary->{answer} = ['X'];
$boundary->{radio} = ['a1'];
($exit, $log) = run_build($boundary);
is($exit, 0, 'a 200-byte single part fits with the BASTION prefix') or diag($log);
$boundary->{title} .= 'z';
($exit, $log) = run_build($boundary);
is($exit, 0, 'a 187-byte unit fits the numbered BASTION prefix') or diag($log);
$boundary->{title} .= 'z';
rejects($boundary, qr/radio cannot fit without truncation: 202 UTF-8 bytes/, 'UTF-8 unit exceeds multipart capacity');

my $parts = fixture('fake-parts');
$parts->{title} = 'Pretend test';
$parts->{answer} = [('x' x 171) x 5];
$parts->{radio} = [map { "a$_" } (1 .. 4)];
($exit, $log) = run_build($parts);
is($exit, 0, 'four full-length parts fit the limit') or diag($log);
push @{ $parts->{radio} }, 'a5';
rejects($parts, qr/radio cannot fit without truncation: 896 UTF-8 bytes/, 'answer needs a fifth part');

my $sentences = fixture('fake-sentences');
$sentences->{answer} = [('x' x 120) . '.  ' . ('y' x 120) . '!'];
$sentences->{radio} = ['a1'];
($exit, $log) = run_build($sentences);
is($exit, 0, 'long line fits intact when split at a sentence boundary') or diag($log);

done_testing();
