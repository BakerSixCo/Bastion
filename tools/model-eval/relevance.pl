# Scores every (question, passage) pair of evalset.json with a relevance model in llama.cpp,
# to see whether a cut-off can separate questions the passages answer from ones they do not.
#   perl relevance.pl emb <tag> [query-task]     server started with --embedding
#   perl relevance.pl rerank <tag>               server started with --reranking
use strict; use warnings; use JSON::PP; use HTTP::Tiny;
my ($mode, $tag, $task) = @ARGV; $task ||= 'search result';
my $j = JSON::PP->new->utf8->canonical; my $http = HTTP::Tiny->new(timeout => 600); my $url = 'http://127.0.0.1:8089';
open my $h, '<:raw', 'evalset.json' or die; my $set = do { local $/; $j->decode(<$h>) };
sub post { my ($p, $b) = @_; my $r = $http->post("$url$p", { headers => { 'Content-Type' => 'application/json' }, content => $j->encode($b) });
    die "$p $r->{status} " . substr($r->{content}, 0, 300) unless $r->{success}; $j->decode($r->{content}) }
sub cosine { my ($a, $b) = @_; my ($d, $x, $y) = (0, 0, 0); for (0 .. $#$a) { $d += $a->[$_] * $b->[$_]; $x += $a->[$_] ** 2; $y += $b->[$_] ** 2 } $d / (sqrt($x) * sqrt($y) || 1) }
open my $out, '>:raw', "results/relevance-$tag.jsonl" or die;
for my $c (@{$set->{cases}}) {
    next if $c->{cat} eq 'injection';
    my @docs = map { [$_->{label}, substr($_->{text}, 0, 1800)] } @{$c->{passages}}; my @s;
    if ($mode eq 'emb') {
        my $e = post('/v1/embeddings', { input => ["task: $task | query: $c->{question}", map { "title: $_->[0] | text: $_->[1]" } @docs] });
        my @v = map { $_->{embedding} } sort { $a->{index} <=> $b->{index} } @{$e->{data}};
        @s = map { cosine($v[0], $v[$_]) } 1 .. $#v;
    } else {
        my $e = post('/v1/rerank', { query => $c->{question}, documents => [map { "$_->[0]\n$_->[1]" } @docs] });
        $s[$_->{index}] = $_->{relevance_score} for @{$e->{results}};
    }
    print $out $j->encode({ id => $c->{id}, cat => $c->{cat}, scores => [map { sprintf('%.3f', $_) + 0 } @s] }), "\n";
    my $max = (sort { $b <=> $a } @s)[0];
    printf "%s %-10s max=%.3f  [%s]  %s\n", $c->{id}, $c->{cat}, $max, join(' ', map { sprintf '%.2f', $_ } @s), substr($c->{question}, 0, 50);
}
