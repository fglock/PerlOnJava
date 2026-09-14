use strict;
use warnings;
use Test::More;

{
    package Local::ConcatStringify;
    use overload '""' => sub { "stringified($_[0]{value})" }, fallback => 1;
    sub new { bless { value => $_[1] }, $_[0] }
}

{
    package Local::ConcatTie;
    sub TIESCALAR { bless { value => $_[1], fetches => $_[2] }, $_[0] }
    sub FETCH { ${ $_[0]{fetches} }++; return $_[0]{value} }
}

my $plain = 'left' . ':' . 'right';
is($plain, 'left:right', 'ordinary unblessed concatenation');

my $object = Local::ConcatStringify->new('value');
is('prefix:' . $object, 'prefix:stringified(value)',
   'string overload remains active after blessing lookup reuse');

my $fetches = 0;
tie my $tied, 'Local::ConcatTie', 'tied', \$fetches;
is('prefix:' . $tied, 'prefix:tied', 'tied operand is fetched before concatenation');
is($fetches, 1, 'tied operand FETCH executes exactly once');

done_testing;
