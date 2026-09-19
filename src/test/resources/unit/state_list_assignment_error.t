use Test::More;
use feature 'state';

my $ok = eval '($_, state $x) = (); 1';
ok(!$ok, 'state declaration in list assignment is rejected');
like($@, qr/Initialization of state variables in list currently forbidden/,
    'reports the state list assignment error');
done_testing;
