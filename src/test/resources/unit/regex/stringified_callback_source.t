use strict;
use warnings;
use Test::More tests => 5;

our $count = 0;
my $compiled = qr/(?{ ++$count })/;
my $source = "$compiled";

like($source, qr/\(\?\{ \+\+\$count \}\)/,
    'stringified qr preserves callback source');

{
    use re 'eval';
    ok('' =~ /$compiled$source$compiled/,
        'compiled and stringified callbacks compose');
}
is($count, 3, 'stringified callback executes between compiled callbacks');

my $error = '';
eval { '' =~ /$compiled$source/ };
$error = $@;
like($error, qr/Eval-group not allowed at runtime/,
    'stringified callback source still requires use re eval');

my $dynamic = qr/(??{ 'x' })/;
like("$dynamic", qr/\(\?\?\{ 'x' \}\)/,
    'stringified qr preserves dynamic callback source');
