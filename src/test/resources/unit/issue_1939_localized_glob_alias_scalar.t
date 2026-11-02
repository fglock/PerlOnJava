use strict;
use warnings;
use Test::More;

my $destination = 'Issue1939::Destination';
my $slot = 'state_dir';
my $value = 'initial value';
my $inside_localization;

{
    no strict 'refs';
    local *SYM = *{"${destination}::$slot"};
    *SYM = \$value;
    $inside_localization = ${"${destination}::$slot"};
}

is($inside_localization, 'initial value',
    'scalar assignment through a localized glob alias updates the destination slot');

my $after_localization;
{
    no strict 'refs';
    $after_localization = ${"${destination}::$slot"};
}
is($after_localization, 'initial value',
    'destination scalar slot remains assigned after glob localization ends');

$value = 'updated through source';
my $updated_destination;
{
    no strict 'refs';
    $updated_destination = ${"${destination}::$slot"};
}
is($updated_destination, 'updated through source',
    'destination scalar slot aliases the original scalar');

{
    no strict 'refs';
    ${"${destination}::$slot"} = 'updated through destination';
}
is($value, 'updated through destination',
    'assignment through destination updates the aliased scalar');

done_testing;
