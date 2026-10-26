package Types::Standard;

use strict;
use warnings;
use Exporter qw(import);

our @EXPORT_OK = qw(Int);

my $integer_type = bless { name => 'Int' }, 'Type::Tiny';

sub Int {
    return $integer_type;
}

package Type::Tiny;

use strict;
use warnings;

sub name {
    return $_[0]->{name};
}

1;
