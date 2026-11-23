# Qwen3-Reranker asked a stricter question than "is this relevant": does the passage directly
# answer the question? Uses the model's own yes/no format through plain completion.
#   perl strict.pl <tag>      (server started without --reranking)
use strict; use warnings; use JSON::PP; use HTTP::Tiny;
my ($tag) = @ARGV; my $j = JSON::PP->new->utf8->canonical; my $http = HTTP::Tiny->new(timeout => 600);
open my $h, '<:raw', 'evalset.json' or die; my $set = do { local $/; $j->decode(<$h>) };
my $inst = 'Decide whether the Document directly answers the Query. A Document about a related subject that uses the same words but answers a different question does not meet the requirement.';
open my $out, '>:raw', "results/relevance-$tag.jsonl" or die;
for my $c (@{$set->{cases}}) {
    next if $c->{cat} eq 'injection'; my @s;
    for my $p (@{$c->{passages}}) {
        my $prompt = "<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be \"yes\" or \"no\".<|im_end|>\n<|im_start|>user\n<Instruct>: $inst\n<Query>: $c->{question}\n<Document>: $p->{label}\n" . substr($p->{text}, 0, 1800) . "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        my $r = $http->post('http://127.0.0.1:8089/completion', { headers => { 'Content-Type' => 'application/json' },
            content => $j->encode({ prompt => $prompt, n_predict => 1, n_probs => 10, temperature => 0, cache_prompt => JSON::PP::false }) });
        die "$r->{status} " . substr($r->{content}, 0, 300) unless $r->{success};
        my $b = $j->decode($r->{content}); my ($yes, $no) = (0, 0);
        for my $t (@{ $b->{completion_probabilities}[0]{top_logprobs} || $b->{completion_probabilities}[0]{probs} || [] }) {
            my $tok = lc($t->{token} // $t->{tok_str} // ''); my $pr = defined $t->{logprob} ? exp($t->{logprob}) : ($t->{prob} // 0);
            $yes += $pr if $tok =~ /^\s*yes/; $no += $pr if $tok =~ /^\s*no/; }
        push @s, ($yes + $no) ? $yes / ($yes + $no) : -1;
    }
    print $out $j->encode({ id => $c->{id}, cat => $c->{cat}, scores => [map { sprintf('%.3f', $_) + 0 } @s] }), "\n";
    printf "%s %-10s max=%.3f  [%s]  %s\n", $c->{id}, $c->{cat}, (sort { $b <=> $a } @s)[0], join(' ', map { sprintf '%.2f', $_ } @s), substr($c->{question}, 0, 50);
}
