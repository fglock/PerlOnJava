use strict;
use warnings;
use Test::More;
use Tie::Hash;

no warnings 'experimental::smartmatch';

{
    package Smartmatch::Plain;
    sub new { bless { value => 1 }, shift }
}

{
    package Smartmatch::Stringified;
    use overload '""' => sub { 'stringified object' }, fallback => 1;
    sub new { bless { value => 1 }, shift }
}

my $plain = Smartmatch::Plain->new;
my $stringified = Smartmatch::Stringified->new;

ok($plain ~~ qr/Smartmatch::Plain/,
    'a blessed object smartmatches a regex against its normal string form');
ok($stringified ~~ 'stringified object',
    'a blessed object uses its stringification overload for scalar smartmatch');
ok($plain ~~ sub { ref $_[0] eq 'Smartmatch::Plain' },
    'a code predicate receives the original blessed object');

my $error = eval { my $ignored = 'x' ~~ $plain; 1 };
ok(!$error && $@, 'a plain RHS object without a smartmatch overload dies');

my %plain_hash = (alpha => 1, beta => 2);
tie my %tied_hash, 'Tie::StdHash';
%tied_hash = %plain_hash;
ok(\%plain_hash ~~ \%tied_hash,
    'hash smartmatch compares keys through a tied hash iterator');

my $hash_string = '' . \%plain_hash;
ok(%plain_hash ~~ $hash_string,
    'a bare hash smartmatches its own stringified reference asymmetrically');
ok(!($hash_string ~~ %plain_hash),
    'the stringified-reference rule is not symmetric');

done_testing;
