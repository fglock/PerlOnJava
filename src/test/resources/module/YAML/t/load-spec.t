use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 52;

run_load_passes();

__DATA__
===
+++ yaml
- Mark McGwire
- Sammy Sosa
- Ken Griffey

===
+++ yaml
hr:  65
avg: 0.278
rbi: 147

===
+++ yaml
american:
   - Boston Red Sox
   - Detroit Tigers
   - New York Yankees
   - Texas Rangers
national:
   - New York Mets
   - Chicago Cubs
   - Atlanta Braves
   - Montreal Expos

===
+++ yaml
-
  name: Mark McGwire
  hr:   65
  avg:  0.278
  rbi:  147
-
  name: Sammy Sosa
  hr:   63
  avg:  0.288
  rbi:  141

===
+++ yaml
?
    - New York Yankees
    - Atlanta Braves
:
  - 2001-07-02
  - 2001-08-12
  - 2001-08-14
?
    - Detroit Tigers
    - Chicago Cubs
:
  - 2001-07-23

===
+++ yaml
invoice: 34843
date   : 2001-01-23
bill-to:
   given  : Chris
   family : Dumars
product:
   - quantity: 4
     desc    : Basketball
   - quantity: 1
     desc    : Super Hoop

===
+++ yaml
---
name: Mark McGwire
hr:  65
avg: 0.278
rbi: 147
---
name: Sammy Sosa
hr:  63
avg: 0.288
rbi: 141

===
+++ yaml
# Ranking of players by
# season home runs.
---
   - Mark McGwire
   - Sammy Sosa
   - Ken Griffey

===
+++ yaml
#hr:     # Home runs
#   # 1998 record
#   - Mark McGwire
#   - Sammy Sosa
#rbi:    # Runs batted in
#   - Sammy Sosa
#   - Ken Griffey

===
+++ yaml
hr:
   - Mark McGwire
   # Name "Sammy Sosa" scalar SS
   - &SS Sammy Sosa
rbi:
   # So it can be referenced later.
   - *SS
   - Ken Griffey

===
+++ yaml
--- >
    Mark McGwire's
    year was crippled
    by a knee injury.

===
+++ yaml
--- |
    \/|\/|
    / |  |_

===
+++ yaml
--- >-
 Sosa completed
 another fine
 season.

===
+++ yaml
#name: Mark McGwire
#occupation: baseball player
#comments: Mark set a major
#          league home run
#          record in 1998.

===
+++ yaml
years: "1998\t1999\t2000\n"
msg:   "Sosa did fine. \u263A"

===
+++ yaml
- ' \/|\/|  '
- ' / |  |_ '

===
+++ yaml
- [ name         , hr , avg   ]
- [ Mark McGwire , 65 , 0.278 ]
- [ Sammy Sosa   , 63 , 0.288 ]

===
+++ yaml
#Mark McGwire: {hr: 65, avg: 0.278}
#Sammy Sosa:   {hr: 63,
#               avg: 0.288}

===
+++ yaml
invoice: 34843
date   : 2001-01-23
buyer:
  given  : Chris
  family : Dumars
product:
  - Basketball: 4
  - Superhoop:  1

===
+++ yaml
#invoice: !int|dec 34843
#date   : !time 2001-01-23
#buyer: !map
#   given  : !str Chris
#   family : !str Dumars
#product: !seq
# - !str Basketball: !int 4
# - !str Superhoop:  !int 1

===
+++ yaml
#invoice: !str 34843
#date   : !str 2001-01-23

===
+++ yaml
#--- !clarkevans.com/schedule/^entry
#who: Clark C. Evans
#when: 2001-11-18
#hours: !^hours 3
#description: >
#   Wrote up these examples
#   and learned a lot about
#   baseball statistics.

===
+++ yaml
#--- !clarkevans.com/graph/^shape
#- !^circle
#  center: &ORIGIN {x: 73, y: 129}
#  radius: 7
#- !^line [23, 32, 300, 200]
#- !^text
#  center: *ORIGIN
#  color: 0x02FDBA

===
+++ yaml
--- !clarkevans.com/^invoice
invoice: 34843
date   : 2001-01-23
bill-to: &id001
    given  : Chris
    family : Dumars
    address:
        lines: |
            458 Walkman Dr.
            Suite #292
        city    : Royal Oak
        state   : MI
        postal  : 48046
ship-to: *id001
product:
    - sku         : BL394D
      quantity    : 4
      description : Basketball
      price       : 450.00
    - sku         : BL4438H
      quantity    : 1
      description : Super Hoop
      price       : 2392.00
tax  : 251.42
total: 4443.52
comments: >
    Late afternoon is best.
    Backup contact is Nancy
    Billsmer @ 338-4338.

===
+++ yaml
---
Date: 2001-11-23
Time: 15:01:42
User: ed
Warning: >
  This is an error message
  for the log file
---
Date: 2001-11-23
Time: 15:02:31
User: ed
Warning: >
  A slightly different error
  message.
---
Date: 2001-11-23
Time: 15:03:17
User: ed
Fatal: >
  Unknown variable "bar"
Stack:
  - file: TopClass.py
    line: 23
    code: |
      x = MoreObject("345\n")
  - file: MoreClass.py
    line: 58
    code: |
      foo = bar

===
+++ yaml
###################################
## These are four throwaway comment
#
## lines (the second line is empty).
#this: | # Comments may trail lines.
#    contains three lines of text.
#    The third one starts with a
#    # character. This isn't a comment.
#
## These are four throwaway comment
## lines (the first line is empty).
###################################

===
+++ yaml
--- >
This YAML stream contains a single text value.
The next stream is a log file - a sequence of
log entries. Adding an entry to the log is a
simple matter of appending it at the end.

===
+++ yaml
---
at: 2001-08-12T09:25:00.00
type: GET
HTTP: '1.0'
url: '/index.html'
---
at: 2001-08-12T09:25:10.00
type: GET
HTTP: '1.0'
url: '/toc.html'

===
+++ yaml
## The following is a sequence of three documents.
## The first contains an empty mapping, the second
## an empty sequence, and the last an empty string.
#--- {}
#--- [ ]
#--- ''

===
+++ yaml
## All entries in the sequence
## have the same type and value.
#- 10.0
#- !float 10
#- !yaml.org/^float '10'
#- !http://yaml.org/float "\
#  1\
#  0"

===
+++ yaml
## Private types are per-document.
#---
#pool: !!ball
#   number: 8
#   color: black
#---
#bearing: !!ball
#        material: steel

===
+++ yaml
## 'http://domain.tld/invoice' is some type family.
#invoice: !domain.tld/^invoice
#  # 'seq' is shorthand for 'http://yaml.org/seq'.
#  # This does not effect '^customer' below
#  # because it is does not specify a prefix.
#  customers: !seq
#    # '^customer' is shorthand for the full
#    # notation 'http://domain.tld/customer'.
#    - !^customer
#      given : Chris
#      family : Dumars

===
+++ yaml
## It is possible to use XML namespace URIs as
## YAML namespaces. Using the ancestor's URI
## allows specifying it only once. The $ separates
## between the XML namespace URI and the tag name.
#doc: !http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd$^html
# - !^body
#  - !^p This is an HTML paragraph.

===
+++ yaml
anchor : &A001 This scalar has an anchor.
override : &A001 >
 The alias node below is a
 repeated use of this value.
alias : *A001

===
+++ yaml
#empty: []
#in-line: [ one, two, three # May span lines,
#         , four,           # indentation is
#           five ]          # mostly ignored.
#nested:
# - First item in top sequence
# -
#  - Subordinate sequence entry
# - >
#  A multi-line
#  sequence entry
# - Sixth item in top sequence

===
+++ yaml
#empty: {}
#in-line: { one: 1, two: 2 }
#spanning: { one: 1,
#   two: 2 }
#nested:
# first : First entry
# second:
#  key: Subordinate mapping
# third:
#  - Subordinate sequence
#  - { }
#  - Previous mapping is empty.
#  - A key: value pair in a sequence.
#    A second: key:value pair.
#  - The previous entry is equal to the following one.
#  -
#   A key: value pair in a sequence.
#   A second: key:value pair.
# !float 12 : This key is a float.
# ? >
#  ?
# : This key had to be protected.
# "\a" : This key had to be escaped.
# ? >
#  This is a
#  multi-line
#  folded key
# : Whose value is
#   also multi-line.
# ?
#  - This key
#  - is a sequence
# :
#  - With a sequence value.
# ?
#  This: key
#  is a: mapping
# :
#  with a: mapping value.

===
+++ yaml
empty: |
detected: |
 The \ ' " characters may be
 freely used. Leading white
    space is significant.

 All line breaks are significant,
 including the final one. Thus
 this value contains one empty
 line and ends with a line break,
 but does not start with one.

# Comments may follow a nested
# scalar value. They must be
# less indented.

# Explicit indentation must
# be given in all the three
# following cases.
leading spaces: |2
      This value starts with four
  spaces. It ends with one line
  break and an empty comment line.

leading line break: |2

  This value starts with
  a line break and ends
  with one.
leading comment indicator: |2
  # first line starts with a
  #. This value does not start
  with a line break but ends
  with one.
# Explicit indentation may
# also be given when it is
# not required.
redundant: |2
  This value is indented 2 spaces.
stripped: |-
  This contains no newline.

kept: |+
  This contains two newlines.

# Comments may follow.

===
+++ yaml
#empty: >
#detected: >
# Line feeds are converted
# to spaces, so this value
# contains no line breaks
# except for the final one.
#
#explicit: >2
#
#  An empty line, either
#  at the start or in
#  the value:
#
#  Is interpreted as a
#  line break. Thus this
#  value contains three
#  line breaks.
#
#stripped: >-1
#   This starts with a space
#  and contains no newline.
#
#kept: >1+
#   This starts with a space
#  and contains two newlines.
#
#indented: >
#    This is a folded
#    paragraph followed
#    by a list:
#     * first entry
#     * second entry
#    Followed by another
#    folded paragraph,
#    another list:
#
#     * first entry
#
#     * second entry
#
#    And a final folded
#    paragraph.
#block: |    # Equal to above.
#    This is a folded paragraph followed by a list:
#     * first entry
#     * second entry
#    Followed by another folded paragraph and list:
#
#     * first entry
#
#     * second entry
#
#    And a final folded paragraph.
#
## Explicit comments may follow
## but must be less indented.

===
+++ yaml
#empty: ''
#second: '! : \ etc. can be used freely.'
#third: 'a single quote '' must be escaped.'
#span: 'this contains
#      six spaces
#
#      and one
#      line break'

===
+++ yaml
#empty: ""
#second: "! : etc. can be used freely."
#third: "a \" or a \\ must be escaped."
#fourth: "this value ends with an LF.\n"
#span: "this contains
#  four  \
#      spaces"

===
+++ yaml
#first: There is no unquoted empty string.
#second: 12          ## This is an integer.
#third: !str 12      ## This is a string.
#span: this contains
#      six spaces
#
#      and one
#      line break
#indicators: this has no comments.
#            #foo and bar# are
#            all text.
#in-line: [ can span
#           lines, # comment
#           like
#           this ]
#note: { one-line keys: but
#        multi-line values }

===
+++ yaml
## The following are equal seqs
## with different identities.
#in-line: [ one, two ]
#spanning: [ one,
#     two: ]
#nested:
#  - one
#  - two

===
+++ yaml
# The following are equal maps
# with different identities.
in-line: { one: 1, two: 2 }
nested:
    one: 1
    two: 2

===
+++ yaml
#- 12 # An integer
## The following scalars
## are loaded to the
## string value '1' '2'.
#- !str 12
#- '12'
#- "12"
#- "\
# 1\
# 2\
# "

===
+++ yaml
#canonical: ~
#verbose: (null)
#sparse:
# - ~
# - Second entry.
# - (nil)
# - This sequence has 4 entries, two with values.
#three: >
# This mapping has three keys,
# only two with values.

===
+++ yaml
#canonical: -
#logical:  (true)
#informal: (no)

===
+++ yaml
#canonical: 12345
#decimal: +12,345
#octal: 014
#hexadecimal: 0xC

===
+++ yaml
#canonical: 1.23015e+3
#exponential: 12.3015e+02
#fixed: 1,230.15
#negative infinity: (-inf)
#not a number: (NaN)

===
+++ yaml
canonical: 2001-12-15T02:59:43.1Z
valid iso8601: 2001-12-14t21:59:43.10-05:00
space separated: 2001-12-14 21:59:43.10 -05:00
date (noon UTC): 2002-12-14

===
+++ yaml
#canonical: !binary "\
# R0lGODlhDAAMAIQAAP//9/X17unp5WZmZgAAAOf\
# n515eXvPz7Y6OjuDg4J+fn5OTk6enp56enmlpaW\
# NjY6Ojo4SEhP/++f/++f/++f/++f/++f/++f/++\
# f/++f/++f/++f/++f/++f/++f/++SH+Dk1hZGUg\
# d2l0aCBHSU1QACwAAAAADAAMAAAFLCAgjoEwnuN\
# AFOhpEMTRiggcz4BNJHrv/zCFcLiwMWYNG84Bww\
# EeECcgggoBADs="
#base64: !binary |
# R0lGODlhDAAMAIQAAP//9/X17unp5WZmZgAAAOf
# n515eXvPz7Y6OjuDg4J+fn5OTk6enp56enmlpaW
# NjY6Ojo4SEhP/++f/++f/++f/++f/++f/++f/++
# f/++f/++f/++f/++f/++f/++f/++SH+Dk1hZGUg
# d2l0aCBHSU1QACwAAAAADAAMAAAFLCAgjoEwnuN
# AFOhpEMTRiggcz4BNJHrv/zCFcLiwMWYNG84Bww
# EeECcgggoBADs=
#description: >
# The binary value above is a tiny arrow
# encoded as a gif image.

===
+++ yaml
## Old schema
#---
#link with:
#  - library1.dll
#  - library2.dll
#
## New schema
#---
#link with:
#  - = : library1.dll
#    version: 1.2
#  - = : library2.dll
#    version: 2.1

===
+++ yaml
#"!": These three keys
#"&": had to be quoted
#"=": and are normal strings.
## NOTE: the following encoded node
## should NOT be serialized this way.
#encoded node :
# !special '!' : '!type'
# !special '&' : 12
# = : value
## The proper way to serialize the
## above structure is as follows:
#node : !!type &12 value
