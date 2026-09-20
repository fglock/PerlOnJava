use strict;
use warnings;
use Test::More;

my $named = 'name=perl';
ok($named =~ /name=(?<language>\w+)/, 'named capture establishes named-capture state');
is($+{language}, 'perl', 'named capture is visible through %+');
is_deeply($-{language}, ['perl'], 'named capture is visible through %-');

my $plain = 'plain text';
ok($plain =~ /plain/, 'capture-free match succeeds');
is_deeply({ %+ }, {}, 'capture-free match clears %+ after a named capture');
is_deeply({ %- }, {}, 'capture-free match clears %- after a named capture');

my $global = 'aba';
ok($global =~ /a/g, 'first capture-free global match succeeds');
is_deeply({ %+ }, {}, 'capture-free global match keeps %+ empty');
ok($global =~ /a/g, 'second capture-free global match succeeds');
is_deeply({ %- }, {}, 'capture-free global match keeps %- empty');

done_testing;
