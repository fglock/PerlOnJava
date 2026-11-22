use strict;
use warnings;
use Test::More tests => 2;

my $href = { a => 1, b => 2, c => 3 };
no strict 'refs';

eval q{%$href->{a}};
like($@, qr/Can't use a hash as a reference/, 'hash dereference before arrow is an error');

eval q{%$href->{"b","c"}};
like($@, qr/Can't use a hash as a reference/, 'hash slice dereference before arrow is an error');
