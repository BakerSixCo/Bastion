# Compares subject-check variants on free-text answers: how many trap answers pass (bad) and how many
# answers to answerable questions pass.  EVALSET=<set> perl covervar.pl <plain-tag>
use strict; use warnings; use JSON::PP; my $j=JSON::PP->new->utf8;
sub slurp { open my $h,'<:raw',$_[0] or die $_[0]; local $/; $j->decode(<$h>) }
my $set=slurp($ENV{EVALSET}); my %case=map{$_->{id}=>$_}@{$set->{cases}}; my $NO=$set->{noAnswer};
do './cover-lib.pl' or die $@||$!;
my @syn=([qw(purif disinfect sanitiz steriliz treat)],[qw(leg arm limb extremit)],[qw(rain wet damp)],[qw(car vehicle truck auto)],[qw(clorox bleach)],[qw(tylenol paracetamol acetaminophen)],[qw(creek stream river)]);
my %extra=map{$_=>1}qw(without caught catch quart gallon pint long much many);
my $refusal=qr/\b(do|does|did)\s+not\s+(contain|provide|mention|include|say|cover|address|specify|state|give|list|have)\b|\bno (information|answer|mention)\b/i;
sub miss { my ($q,$text,$v)=@_; my @t=terms($q); @t=grep{!$extra{$_}}@t if $v=~/x/; my @m;
  for my $t (@t){ next if $text=~/\b\Q$t\E/i; if($v=~/s/){ my ($g)=grep{ grep{ index($t,$_)==0 || index($_,$t)==0 } @$_ } @syn; next if $g && grep{ $text=~/\b\Q$_\E/i } @$g; } push @m,$t }
  return 0 if $v=~/1/ && @t>=4 && @m<=1; scalar @m }
for my $v ('base','x','s','xs','1','xs1') { my %n; my @trapids;
  open my $f,'<:raw',"results/$ARGV[0].jsonl" or die; while(<$f>){ my $r=$j->decode($_); my $c=$case{$r->{id}} or next; my $a=$r->{answer}//'';
    next if $a=~/^\s*$/ || index(lc $a,lc $NO)>=0 || substr($a,0,250)=~$refusal;
    my %cited; $cited{$_}++ for map{split /\s*,\s*/} $a=~/\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\]/g; my @src=grep{$cited{$_->{index}}}@{$c->{passages}}; @src=@{$c->{passages}} unless @src;
    my $text=join "\n",map{"$_->{label}\n$_->{text}"}@src; next if miss($c->{question},$text,$v);
    my $k=$c->{cat} eq 'absent'?'trap':'ans'; $n{$k}++; push @trapids,$r->{id} if $k eq 'trap' }
  printf "%-5s answerable pass %3d | traps pass %d (%s)\n",$v,$n{ans}//0,$n{trap}//0,join(",",@trapids) }
