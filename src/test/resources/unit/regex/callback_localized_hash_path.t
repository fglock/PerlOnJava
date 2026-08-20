use strict;
use warnings;
use Test::More tests => 15;

our ($hash, @seen);
$hash->{existing} = 'outer';

ok('JohnSmith' =~ /(John)(?{ local $hash->{name} = $^N })
                     (Smith)(?{ push @seen, "$hash->{name}:$^N" })/x,
   'match with localized hash element succeeds');
is_deeply(\@seen, ['John:Smith'],
          'a later callback sees a localized element from the same path');
ok(!exists $hash->{name}, 'absent hash element is restored after the match');

@seen = ();
ok('ab' =~ /a(?{ local $hash->{existing} = 'failed' })c
             |ab(?{ push @seen, $hash->{existing} })/x,
   'backtracking alternative succeeds');
is_deeply(\@seen, ['outer'],
          'a failed callback path unwinds its localized element');
is($hash->{existing}, 'outer', 'existing element is restored after matching');

@seen = ();
ok('ab' =~ /a(?{ { local $hash->{existing} = 'nested' } })
             b(?{ push @seen, $hash->{existing} })/x,
   'nested callback block succeeds');
is_deeply(\@seen, ['outer'],
          'a nested lexical block restores local before the next callback');

my ($failed_scalar, $failed_autoviv) = (0, undef);
ok('abc' !~ /^a(?{
        $failed_scalar = 1;
        $failed_autoviv->{value} = 2;
    })b$/,
   'whole-match failure abandons a plain callback path');
is($failed_scalar, 0, 'failed plain callback restores a scalar mutation');
ok(!defined $failed_autoviv,
   'failed plain callback restores a newly autovivified aggregate');

ok('ab' =~ /^a(?{ $failed_scalar = 3 })b$/ && $failed_scalar == 3,
   'successful plain callback commits its scalar mutation');

my ($exception_scalar, @exception_values) = (0, ());
my $error = '';
eval {
    'a' =~ /a(?{
        $exception_scalar = 4;
        push @exception_values, 'kept';
        die "callback exception\n";
    })/;
    1;
} or $error = $@;
like($error, qr/callback exception/,
     'plain callback exception crosses the matcher boundary');
is($exception_scalar, 4,
   'scalar mutation before a callback exception persists');
is_deeply(\@exception_values, ['kept'],
          'aggregate mutation before a callback exception persists');
