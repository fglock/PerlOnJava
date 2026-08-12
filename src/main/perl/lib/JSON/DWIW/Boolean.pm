package JSON::DWIW::Boolean;

use strict;
use warnings;
use overload
    bool => sub { ${$_[0]} ? 1 : 0 },
    '0+' => sub { ${$_[0]} ? 1 : 0 };

sub new { my $value = $_[1] ? 1 : 0; bless \$value, ref($_[0]) || $_[0] }
sub true { shift->new(1) }
sub false { shift->new(0) }
sub as_bool { ${$_[0]} ? 1 : undef }

1;
