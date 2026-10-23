use strict;
use warnings;
use Test::More tests => 10;

use Email::Address::XS qw(parse_email_addresses parse_email_groups format_email_groups);

my @addresses = parse_email_addresses(
    '"<look@like.address>," <test@example.com>, "Döy" <doy@example.com>'
);
is(scalar @addresses, 2, 'angle brackets inside a quoted phrase are not mailbox delimiters');
is($addresses[0]->phrase, '<look@like.address>,', 'quoted display phrase is parsed');
is($addresses[0]->address, 'test@example.com', 'the actual angle address is selected');
my @object_group = parse_email_groups($addresses[0]);
is($object_group[0], undef, 'an address object is treated as an ungrouped address');
is($object_group[1][0], $addresses[0], 'an address object is preserved when formatting groups');
my @address_list = parse_email_groups('first@example.com, second@example.com');
is(scalar @{$address_list[1]}, 2, 'ordinary comma-separated addresses share one group');

my @groups = parse_email_groups(
    '"MIME:;": "Winston: Smith" <winston@example.com>, '
        . '"Julia:; " <julia@example.com>;'
);
is($groups[0], 'MIME:;', 'quoted group name is parsed');
is(scalar @{$groups[1]}, 2, 'group members are parsed');
is($groups[1][0]->phrase, 'Winston: Smith', 'quoted colon phrase is preserved');
is(
    format_email_groups(@groups),
    '"MIME:;": "Winston: Smith" <winston@example.com>, "Julia:; " <julia@example.com>;',
    'group and special phrases are quoted when formatted',
);
