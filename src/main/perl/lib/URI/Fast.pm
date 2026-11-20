package URI::Fast;

our $XS_VERSION = our $VERSION = '0.55';
$VERSION =~ tr/_//;

use utf8;
use strict;
use warnings;
no strict 'refs';

use Carp;
use Exporter;
use Scalar::Util qw(refaddr);

require XSLoader;
XSLoader::load('URI::Fast', $XS_VERSION);

use Exporter 'import';

our @EXPORT_OK = qw(
  uri_split 
  uri
  iri
  abs_uri
  html_url
  encode uri_encode url_encode
  decode uri_decode url_decode
);

require URI::Fast::IRI;

use overload
  'eq' => sub{ $_[0]->compare($_[1]) },
  '""' => \&to_string;

sub auth {
  my ($self, $val) = @_;

  if (@_ == 2) {
    if (ref $val) {
      $self->usr($val->{usr}   || '');
      $self->pwd($val->{pwd}   || '');
      $self->host($val->{host} || '');
      $self->port($val->{port} || '');
    }
    else {
      $self->set_auth($val);
    }
  }

  return $self->get_auth
    if defined wantarray;
}

# Path is slightly more complicated as it can parse the path
sub path {
  my ($self, $val) = @_;

  if (@_ == 2) {
    if (ref $val) {
      $self->set_path_array($val);
    } else {
      $self->set_path($val);
    }
  }

  if (wantarray) {
    @{ $self->split_path };
  } elsif (defined wantarray) {
    $self->get_path;
  }
}

# Queries may be set with either a string or a hash ref
sub query {
  my ($self, $val, $sep) = @_;

  if (@_ > 1) {
    if (ref $val) {
      $self->clear_query;
      $self->param($_, $val->{$_}, $sep)
        foreach keys %$val;
    }
    else {
      $self->set_query($val);
    }
  }

  return unless defined(wantarray);         # void context
  return $self->get_query unless wantarray; # scalar context
  return %{ $self->query_hash };            # list context
}

sub query_hash {
  my $self = shift;
  $self->query(@_) if @_;
  $self->get_query_hash;
}

sub query_keys {
  keys %{ $_[0]->get_query_keys };
}

sub param {
  my ($self, $key, $val, $sep) = @_;
  $sep ||= '&';

  if (@_ > 2) {
    $val = ref     $val ? $val
         : defined $val ? [$val]
         : [];

    $self->set_param($key, $val, $sep);
  }

  # No return value in void context
  return unless defined(wantarray) && $key;

  my $params = $self->get_param($key);
  return unless @$params;

  return wantarray     ? @$params
       : @$params == 1 ? $params->[0]
       : croak("param: multiple values encountered for query parameter '$key' when called in SCALAR context");
}

sub add_param {
  my ($self, $key, $val, $sep) = @_;
  $self->param($key, [$self->param($key), $val], $sep);
}

sub append {
  my $self = shift;

  foreach my $segment (@_) {
    if ($segment =~ /^\?/) {
      my $q = uri($segment)->query_hash;

      foreach my $key (keys %$q) {
        $self->add_param($key => $_)
          foreach @{ $q->{$key} };
      }
    }
    elsif (my ($frag) = $segment =~ /^#(.*)$/) {
      $self->frag($frag);
    }
    else {
      my @segments = ($self->path, split('/', $segment));
      $self->path(\@segments);
    }
  }

  return $self;
}

sub _cmp ($$) {
  if (defined $_[0]) {
    return if !defined $_[1];
    return if $_[0] ne $_[1];
  }
  elsif (defined $_[1]) {
    return;
  }

  return 1;
}

sub compare {
  my ($self, $other) = @_;
  $other = uri($other);

  return unless _cmp($self->scheme, $other->scheme)
    && _cmp($self->usr,  $other->usr)
    && _cmp($self->pwd,  $other->pwd)
    && _cmp($self->host, $other->host)
    && _cmp($self->port, $other->port)
    && _cmp($self->frag, $other->frag);

  my @spath = $self->path;
  my @opath = $other->path;
  return unless @spath == @opath;

  foreach (0 .. $#spath) {
    return unless _cmp($spath[$_], $opath[$_]);
  }

  my $sparam = $self->query_hash;
  my $oparam = $other->query_hash;

  foreach my $k (keys %$sparam) {
    return unless exists $oparam->{$k};
    return unless @{$sparam->{$k}} == @{$oparam->{$k}};

    foreach (0 .. $#{$sparam->{$k}}) {
      return unless _cmp($sparam->{$k}[$_], $oparam->{$k}[$_]);
    }
  }

  return 1;
}

#-------------------------------------------------------------------------------
# Relativism
#-------------------------------------------------------------------------------
sub relative {
  my ($self, $base) = @_;
  my $rel = (ref $self)->new("$self");

  my $rpath = $self->path;
  ($_, $_, my $bpath) = uri_split("$base");

  if ($bpath =~ m|/$| && $rpath eq $bpath) {
    $rel->path('./');
  }
  else {
    # Ensure both paths begin with '/'
    $rpath = "/$rpath" unless $rpath =~ m|^/|;
    $bpath = "/$bpath" unless $bpath =~ m|^/|;

    # Remove common leading path segments
    my ($idx, $brk) = (1, 0);

    # Scan for matching segments
    while (1) {
      # Locate the next delimiter
      $brk = index($rpath, '/', $idx);

      last if $brk < 0;                           # not found
      last if $brk != index($bpath, '/', $idx);   # no match: different segment lengths
      last if substr($rpath, $idx, $brk - $idx)   # no match: segments have different string values
           ne substr($bpath, $idx, $brk - $idx);

      $idx = $brk + 1;                            # move past delimiter
    }

    # $idx now matches the slash after the common prefix; remove path contents
    # up to that point.
    substr($rpath, 0, $idx) = '';
    substr($bpath, 0, $idx) = '';

    # Add ../ for each remaining base path segment
    while ($bpath =~ m|/|gc) {
      $rpath = "../$rpath";
    }

    # An empty path becomes ./
    $rpath ||= './';

    $rel->path($rpath);
  }

  $rel->clear_scheme;
  $rel->clear_auth;

  return $rel;
}

sub rel {
  goto \&relative;
}

sub _walk {
  my ($ref, $sub) = @_;
  my @stack = ($ref);
  my %seen;

  while (my $elt = shift @stack) {
    my $ref = ref $elt;

    if (defined $ref) {
      my $addr = refaddr $elt;
      next if exists $seen{ $addr };
      $seen{ $addr } = 1;

      if ($ref eq 'ARRAY') {
        for (0 .. @$elt - 1) {
          if (ref $elt->[$_]) {
            push @stack, $elt->[$_];
          }
          elsif (defined $elt->[$_]) {
            $elt->[$_] = $sub->( $elt->[$_] );
          }
        }
      }
      elsif ($ref eq 'HASH') {
        foreach (keys %$elt) {
          if (ref $elt->{$_}) {
            push @stack, $elt->{$_};
          }
          elsif (defined $elt->{$_}) {
            $elt->{$_} = $sub->( $elt->{$_} );
          }
        }
      }
      elsif ($ref eq 'SCALAR') {
        push @stack, $$elt;
      }
    }
  }

  $ref;
}

sub escape_tree {
  my ($ref, @args) = @_;
  ref $ref || croak "escape_tree: reference expected";

  my $sub = sub{
    push @_, @args;
    goto \&encode;
  };

  _walk($ref, $sub);
}

sub unescape_tree {
  my ($ref, @args) = @_;
  ref $ref || croak "unescape_tree: reference expected";

  my $sub = sub{
    push @_, @args;
    goto \&decode;
  };

  _walk($ref, $sub);
}

=encoding UTF8

=head1 NAME

URI::Fast - A fast(er) URI parser

=head1 SYNOPSIS

  use URI::Fast qw(uri);

  my $uri = uri 'http://www.example.com/some/path?fnord=slack&foo=bar';

  if ($uri->scheme =~ /http(s)?/) {
    my @path  = $uri->path;
    my $fnord = $uri->param('fnord');
    my $foo   = $uri->param('foo');
  }

  if ($uri->path =~ /\/login/ && $uri->scheme ne 'https') {
    $uri->scheme('https');
    $uri->param('upgraded', 1);
  }

=head1 DESCRIPTION

C<URI::Fast> is a faster alternative to L<URI>. It is written in C and provides
basic parsing and modification of a URI.

L<URI> is an excellent module; it is battle-tested, robust, and handles many
edge cases. As a result, it is rather slower than it would otherwise be for
more trivial cases, such as inspecting the path or updating a single query
parameter.

=head1 EXPORTED SUBROUTINES

Subroutines are exported on demand.

=head2 uri

Accepts a URI string, minimally parses it, and returns a C<URI::Fast> object.

Note: passing a C<URI::Fast> instance to this routine will cause the object to
be interpolated into a string (via L</to_string>), effectively creating a clone
of the original C<URI::Fast> object.

=head2 iri

Similar to L</uri>, but returns a C<URI::Fast::IRI> object. A C<URI::Fast::IRI>
differs from a C<URI::Fast> in that UTF-8 characters are permitted and will not
be percent-encoded when modified.

=head2 abs_uri

Builds a new C<URI::Fast> from a relative URI string and makes it L</absolute>
in relation to C<$base>.

  my $uri = abs_uri 'some/path', 'http://www.example.com/fnord';
  $uri->to_string; # "http://www.example.com/fnord/some/path"

=head2 html_url

Parses a URI string, removing whitespace characters ignored in URLs found in
HTML documents, replacing backslashes with forward slashes, and making the
URL L</normalize>d.

If a base URL is specified, the C<URI::Fast> object returned will be made
L</absolute> relative to that base URL.

  # Resulting URL is "https://www.slashdot.org/recent"
  my $url = html_url '//www.slashdot.org\recent', "https://www.slashdot.org";

=head2 uri_split

Behaves (hopefully) identically to L<URI::Split>, but roughly twice as fast.

=head2 encode/decode/uri_encode/uri_decode

See L</ENCODING>.

=head1 CONSTRUCTORS

=head2 new

If desired, both C<URI::Fast> and L<URI::Fast::IRI> may be instantiated using
the default OO-flavored constructor, C<new>.

  my $uri = URI::Fast->new('http://www.example.com');

=head2 new_abs

OO equivalent to L</abs_uri>.

=head2 new_html_url

OO equivalent to L</html_url>.

=head1 ATTRIBUTES

All attributes serve as full accessors, allowing the URI segment to be both
retrieved and modified.

=head2 RAW ACCESSORS

Each attribute defines a C<raw_*> method, which returns the raw, encoded string
value for that attribute. If a new value is passed, it will set the field to
the raw, unchanged value without checking it or changing it in any way.

=head2 CLEARERS

Each attribute further has a matching clearer method (C<clear_*>) which unsets
its value.

=head2 ACCESSORS

In general, accessors accept an I<unencoded> string and set their slot value to
the I<encoded> value. They return the decoded value. See L</ENCODING> for an in
depth description of their behavior as well as an explanation of the more
complex behavior of compound fields.

=head3 scheme

Gets or sets the scheme portion of the URI (e.g. C<http>), excluding C<://>.

=head3 auth

The authorization section is composed of the username, password, host name, and
port number:

  hostname.com
  someone@hostname.com
  someone:secret@hostname.com:1234

Setting this field may be done with a string (see the note below about
L</ENCODING>) or a hash reference of individual field names (C<usr>, C<pwd>,
C<host>, and C<port>). In both cases, the existing values are completely
replaced by the new values and any values missing from the caller-supplied
input are deleted.

=head4 usr

The username segment of the authorization string. Updating this value alters
L</auth>.

=head4 pwd

The password segment of the authorization string. Updating this value alters
L</auth>.

=head4 host

The host name segment of the authorization string. May be a domain string or an
IP address. If the host is an IPV6 address, it must be surrounded by square
brackets (per spec), which are included in the host string. Updating this value
alters L</auth>.

=head4 port

The port number segment of the authorization string. Updating this value alters
L</auth>.

=head3 path

In scalar context, returns the entire path string. In list context, returns a
list of path segments, split by C</>.

  my $uri = uri '/foo/bar';
  my $path = $uri->path;  # "/foo/bar"
  my @path = $uri->path;  # ("foo", "bar")

The path may also be updated using either a string or an array ref of segments:

  $uri->path('/foo/bar');
  $uri->path(['foo', 'bar']);

This differs from the behavior of L<URI/path_segments>, which considers the
leading slash separating the path from the authority section to be an
individual segment. If this behavior is desired, the lower level
C<split_path_compat> is available. C<split_path_compat> (and its partner,
C<split_path>), always return an array reference.

  my $uri = uri '/foo/bar';
  $uri->split_path;         # ['foo', 'bar'];
  $uri->split_path_compat;  # ['', 'foo', 'bar'];

=head3 query

In scalar context, returns the complete query string, excluding the leading
C<?>. The query string may be set in several ways.

  $uri->query("foo=bar&baz=bat"); # note: no percent-encoding performed
  $uri->query({foo => 'bar', baz => 'bat'}); # foo=bar&baz=bat
  $uri->query({foo => 'bar', baz => 'bat'}, ';'); # foo=bar;baz=bat

In list context, returns a hash ref mapping query keys to array refs of their
values (see L</query_hash>).

Both '&' and ';' are treated as separators for key/value parameters.

=head3 frag

The fragment section of the URI, excluding the leading C<#>.

=head3 fragment

An alias of L</frag>.

=head1 METHODS

=head2 query_keys

Does a fast scan of the query string and returns a list of unique parameter
names that appear in the query string.

Both '&' and ';' are treated as separators for key/value parameters.

=head2 query_hash

Scans the query string and returns a hash ref of key/value pairs. Values are
returned as an array ref, as keys may appear multiple times. Both '&' and ';'
are treated as separators for key/value parameters.

May optionally be called with a new hash of parameters to replace the query
string with, in which case keys may map to scalar values or arrays of scalar
values. As with all query setter methods, a third parameter may be used to
explicitly specify the separator to use when generating the new query string.

=head2 param

Gets or sets a parameter value. Setting a parameter value will replace existing
values completely; the L</query> string will also be updated. Setting a
parameter to C<undef> deletes the parameter from the URI.

  $uri->param('foo', ['bar', 'baz']);
  $uri->param('fnord', 'slack');

  my $value_scalar = $uri->param('fnord'); # fnord appears once
  my @value_list   = $uri->param('foo');   # foo appears twice
  my $value_scalar = $uri->param('foo');   # croaks; expected single value but foo has multiple

  # Delete parameter
  $uri->param('foo', undef); # deletes foo

  # Ambiguous cases
  $uri->param('foo', '');  # foo=
  $uri->param('foo', '0'); # foo=0
  $uri->param('foo', ' '); # foo=%20

Both '&' and ';' are treated as separators for key/value parameters when
parsing the query string. An optional third parameter explicitly selects the
character used to separate key/value pairs.

  $uri->param('foo', 'bar', ';'); # foo=bar
  $uri->param('baz', 'bat', ';'); # foo=bar;baz=bat

When unspecified, '&' is chosen as the default. I<In either case, all
separators in the query string will be normalized to the chosen separator>.

  $uri->param('foo', 'bar', ';'); # foo=bar
  $uri->param('baz', 'bat', ';'); # foo=bar;baz=bat
  $uri->param('fnord', 'slack');  # foo=bar&baz=bat&fnord=slack

=head2 add_param

Updates the query string by adding a new value for the specified key. If the
key already exists in the query string, the new value is appended without
altering the original value.

  $uri->add_param('foo', 'bar'); # foo=bar
  $uri->add_param('foo', 'baz'); # foo=bar&foo=baz

This method is simply sugar for calling:

  $uri->param('key', [$uri->param('key'), 'new value']);

As with L</param>, the separator character may be specified as the final
parameter. The same caveats apply with regard to normalization of the query
string separator.

  $uri->add_param('foo', 'bar', ';'); # foo=bar
  $uri->add_param('foo', 'baz', ';'); # foo=bar;foo=baz

=head2 query_keyset

Allows modification of the query string in the manner of a set, using keys
without C<=value>, e.g. C<foo&bar&baz>. Accepts a hash ref of keys to update.
A truthy value adds the key, a falsey value removes it. Any keys not mentioned
in the update hash are left unchanged.

  my $uri = uri '&baz&bat';
  $uri->query_keyset({foo => 1, bar => 1}); # baz&bat&foo&bar
  $uri->query_keyset({baz => 0, bat => 0}); # foo&bar

If there are key-value pairs in the query string as well, the behavior of
this method becomes a little more complex. When a key is specified in the
hash update hash ref, a positive value will leave an existing key/value pair
untouched. A negative value will remove the key and value.

  my $uri = uri '&foo=bar&baz&bat';
  $uri->query_keyset({foo => 1, baz => 0}); # foo=bar&bat

An optional second parameter may be specified to control the separator
character used when updating the query string. The same caveats apply with
regard to normalization of the query string separator.

=head2 append

Serially appends path segments, query strings, and fragments, to the end of the
URI. Each argument is added in order. If the segment begins with C<?>, it is
assumed to be a query string and it is appended using L</add_param>. If the
segment begins with C<#>, it is treated as a fragment, replacing any existing
fragment. Otherwise, the segment is treated as a path fragment and appended to
the path.

  my $uri = uri 'http://www.example.com/foo?k=v';
  $uri->append('bar', 'baz/bat', '?k=v1&k=v2', '#fnord', 'slack');
  # 'http://www.example.com/foo/bar/baz/bat/slack?k=v&k=v1&k=v2#fnord'


=head2 to_string

=head2 as_string

=head2 "$uri"

Stringifies the URI, encoding output as necessary. String interpolation is
overloaded.

=head2 compare

=head2 $uri eq $other

Compares the URI to another, returning true if the URIs are equivalent.
Overloads the C<eq> operator.

=head2 clone

Sugar for:

  my $uri = uri '...';
  my $clone = uri $uri;

=head2 absolute

Builds an absolute URI from a relative URI and a base URI string.
Adheres as strictly as possible to the rules for resolving a target URI in
L<RFC3986 section 5.2|https://www.rfc-editor.org/rfc/rfc3986.txt>. Returns a new
L<URI::Fast> object representing the absolute, merged URI.

  my $uri = uri('some/path')->absolute('http://www.example.com/fnord');
  $uri->to_string; # "http://www.example.com/fnord/some/path"

=head2 abs

Alias of L</absolute>.

=head2 relative

Builds a relative URI using a second URI (either a C<URI::Fast> object or a
string) as a base. Unlike L<URI/rel>, ignores differences in domain and scheme
assumes the caller wishes to adopt the base URL's instead. Aside from that difference,
it's behavior should mimic L<URI/rel>'s.

  my $uri = uri('http://example.com/foo/bar')->relative('http://example.com/foo');
  $uri->to_string; # "foo/bar"

  my $uri = uri('http://example.com/foo/bar/')->relative('http://example.com/foo');
  $uri->to_string; # "foo/bar/"

=head2 rel

Alias of L</relative>.

=head2 normalize

Similar to L<URI/canonical>, performs a minimal normalization on the URI. Only
generic normalization described in the rfc is performed; no scheme-specific
normalization is done. Specifically, the scheme and host members are converted
to lower case, dot segments are collapsed in the path, and any percent-encoded
characters in the URI are converted to upper case.

=head2 canonical

Alias of L</normalize>.

=head1 ENCODING

C<URI::Fast> tries to do the right thing in most cases with regard to reserved
and non-ASCII characters. C<URI::Fast> will fully encode reserved and non-ASCII
characters when setting I<individual> values and return their fully decoded
values. However, the "right thing" is somewhat ambiguous when it comes to
setting compound fields like L</auth>, L</path>, and L</query>.

When setting compound fields with a string value, reserved characters are
expected to be present, and are therefore accepted as-is. Any non-ASCII
characters will be percent-encoded (since they are unambiguous and there is no
risk of double-encoding them). Thus,

  $uri->auth('someone:secret@Ῥόδος.com:1234');
  print $uri->auth; # "someone:secret@%E1%BF%AC%CF%8C%CE%B4%CE%BF%CF%82.com:1234"

On the other hand, when setting these fields with a I<reference> value (assumed
to be a hash ref for L</auth> and L</query> or an array ref for L</path>; see
individual methods' docs for details), each field is fully percent-encoded,
just as if each individual simple slot's setter had been called:

  $uri->auth({usr => 'some one', host => 'somewhere.com'});
  print $uri->auth; # "some%20one@somewhere.com"
  print $uri->usr;; # "some one"

The same goes for return values. For compound fields returning a string,
non-ASCII characters are decoded but reserved characters are not. When
returning a list or reference of the deconstructed field, individual values are
decoded of both reserved and non-ASCII characters.

=head2 '+' vs '%20'

Although no longer part of the standard, C<+> is commonly used as the encoded
space character (rather than C<%20>); it I<is> still official to the
C<application/x-www-form-urlencoded> type, and is treated as a space by
L</decode>.

=head2 encode

Percent-encodes a string for use in a URI. By default, both reserved and UTF-8
chars (C<! * ' ( ) ; : @ & = + $ , / ? # [ ] %>) are encoded.

A second (optional) parameter provides a string containing any characters the
caller does not wish to be encoded. An empty string will result in the default
behavior described above.

For example, to encode all characters in a query-like string I<except> for
those used by the query:

  my $encoded = URI::Fast::encode($some_string, '?&=');

=head2 decode

Decodes a percent-encoded string.

  my $decoded = URI::Fast::decode($some_string);

=head2 uri_encode

=head2 uri_decode

These are aliases of L</encode> and L</decode>, respectively. They were added
to make L<BLUEFEET|https://metacpan.org/author/BLUEFEET> happy after he made
fun of me for naming L</encode> and L</decode> too generically.

In fact, these were originally aliased as C<url_encode> and C<url_decode>, but
due to some pedantic whining on the part of
L<BGRIMM|https://metacpan.org/author/BGRIMM>, they have been renamed to
C<uri_encode> and C<uri_decode>.

=head2 escape_tree

=head2 unescape_tree

Traverses a data structure, escaping or unescaping I<defined> scalar values in
place. Accepts a reference to be traversed. Any further parameters are passed
unchanged to L</encode> or L</decode>. Croaks if the input to escape/unescape
is a non-reference value.

  my $obj = {
    foo => ['bar baz', 'bat%fnord'],
    bar => {baz => 'bat%bat'},
    baz => undef,
    bat => '',
  };

  URI::Fast::escape_tree($obj);

  # $obj is now:
  {
    foo => ['bar%20baz', 'bat%25fnord'],
    bar => {baz => 'bat%25bat'},
    baz => undef,
    bat => '',
  }

  URI::Fast::unescape_tree($obj); # $obj returned to original form

  URI::Fast::escape_tree($obj, '%'); # escape but allow "%"

  # $obj is now:
  {
    foo => ['bar%20baz', 'bat%fnord'],
    bar => {baz => 'bat%bat'},
    baz => undef,
    bat => '',
  }

=head1 CAVEATS

This module is designed to parse URIs according to RFC 3986. Browsers parse
URLs using a different (but similar) algorithm and some strings that are valid
URLs to browsers are not valid URIs to this module. The L</html_url> function
attempts to parse URLs more in line with how browsers do, but no guarantees are
made as HTML standards and browser implementations are an ever shifting
landscape.

=head1 SPEED

See L<URI::Fast::Benchmarks>.

=head1 SEE ALSO

=over

=item L<URI>

The de facto standard.

=item L<RFC 3986|https://www.rfc-editor.org/rfc/rfc3986.txt>

The official standard.

=back

=head1 ACKNOWLEDGEMENTS

Thanks to L<ZipRecruiter|https://www.ziprecruiter.com> for encouraging their
employees to contribute back to the open source ecosystem. Without their
dedication to quality software development this distribution would not exist.

=head1 CONTRIBUTORS

The following people have contributed to this module with patches, bug reports,
API advice, identifying areas where the documentation is unclear, or by making
fun of me for naming certain methods too generically.

=over

=item Andy Ruder

=item Aran Deltac (BLUEFEET)

=item Ben Grimm (BGRIMM)

=item Dave Hubbard (DAVEH)

=item James Messrie

=item Martin Locklear

=item Randal Schwartz (MERLYN)

=item Sara Siegal (SSIEGAL)

=item Tim Vroom (VROOM)

=item Des Daignault (NAWGLAN)

=item Josh Rosenbaum

=back

=head1 AUTHOR

Jeff Ober <sysread@fastmail.fm>

=head1 COPYRIGHT AND LICENSE

This software is copyright (c) 2018 by Jeff Ober. This is free software; you
can redistribute it and/or modify it under the same terms as the Perl 5
programming language system itself.

=cut

1;
